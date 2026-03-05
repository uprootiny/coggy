use std::{env, net::SocketAddr, sync::Arc};

use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::{Html, Json},
    routing::get,
    Router,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::net::TcpListener;
use tokio::sync::Mutex;

use coggy::{atomspace::AtomSpace, cogloop, ecan::EcanConfig, ontology};

static INDEX_HTML: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/static/index.html"));

#[derive(Clone)]
struct AppState {
    space: Arc<Mutex<AtomSpace>>,
    ecan: Arc<EcanConfig>,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_target(false)
        .with_max_level(tracing::Level::INFO)
        .init();

    let mut base_space = AtomSpace::new();
    ontology::load_base_ontology(&mut base_space);
    let state = AppState {
        space: Arc::new(Mutex::new(base_space)),
        ecan: Arc::new(EcanConfig::default()),
    };

    let port = env::var("COGGY_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(8421);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));

    let app = Router::new()
        .route("/", get(root))
        .route("/api/health", get(health))
        .route("/api/feed", get(feed))
        .route("/api/trace", get(trace))
        .route("/api/focus", get(focus))
        .with_state(state);

    tracing::info!("Serving Coggy web experience on http://{}", addr);

    let listener = TcpListener::bind(addr)
        .await
        .expect("bind to shipping listener");

    axum::serve(listener, app).await.expect("web server to run");
}

async fn root() -> Html<&'static str> {
    Html(INDEX_HTML)
}

async fn health(State(state): State<AppState>) -> Json<HealthResponse> {
    let space = state.space.lock().await;
    Json(HealthResponse {
        status: "ok",
        atoms: space.size(),
        turn: space.turn,
    })
}

async fn focus(State(state): State<AppState>) -> Json<FocusResponse> {
    let space = state.space.lock().await;
    let tops: Vec<_> = space
        .atoms_by_sti(10)
        .into_iter()
        .map(|atom| FocusEntry {
            atom: space.format_atom(atom.id),
            atom_type: format!("{}", atom.atom_type),
            sti: atom.av.sti,
            tv: TruthValueEntry {
                strength: atom.tv.strength,
                confidence: atom.tv.confidence,
            },
        })
        .collect();
    Json(FocusResponse {
        turn: space.turn,
        focus: tops,
    })
}

async fn feed(State(state): State<AppState>) -> Json<FeedResponse> {
    let space = state.space.lock().await;
    let focus_items: Vec<_> = space
        .atoms_by_sti(8)
        .into_iter()
        .map(|atom| FocusEntry {
            atom: space.format_atom(atom.id),
            atom_type: format!("{}", atom.atom_type),
            sti: atom.av.sti,
            tv: TruthValueEntry {
                strength: atom.tv.strength,
                confidence: atom.tv.confidence,
            },
        })
        .collect();
    let types: Vec<_> = space
        .types_present()
        .into_iter()
        .map(|(atom_type, count)| TypeSummary {
            atom_type: format!("{}", atom_type),
            count,
        })
        .collect();
    Json(FeedResponse {
        turn: space.turn,
        focus: focus_items,
        types,
    })
}

async fn trace(
    Query(params): Query<TraceQuery>,
    State(state): State<AppState>,
) -> Result<Json<Value>, (StatusCode, &'static str)> {
    let input = params.input.trim();
    if input.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "input query required"));
    }

    let mut space = state.space.lock().await;
    let result = cogloop::run(&mut space, input, &state.ecan);
    let trace: Vec<_> = result
        .trace
        .iter()
        .map(|step| json!({ "phase": step.phase, "lines": step.lines }))
        .collect();
    let focus: Vec<_> = space
        .atoms_by_sti(6)
        .into_iter()
        .map(|atom| {
            json!({
                "atom": space.format_atom(atom.id),
                "sti": atom.av.sti,
                "tv": {
                    "strength": atom.tv.strength,
                    "confidence": atom.tv.confidence,
                }
            })
        })
        .collect();
    Ok(Json(json!({
        "event": "trace",
        "input": input,
        "turn": result.turn,
        "new_atoms": result.new_atoms,
        "total_atoms": result.total_atoms,
        "inferences": result.inferences,
        "trace": trace,
        "focus": focus,
    })))
}

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
    atoms: usize,
    turn: u32,
}

#[derive(Serialize)]
struct FocusResponse {
    turn: u32,
    focus: Vec<FocusEntry>,
}

#[derive(Serialize)]
struct FeedResponse {
    turn: u32,
    focus: Vec<FocusEntry>,
    types: Vec<TypeSummary>,
}

#[derive(Serialize)]
struct FocusEntry {
    atom: String,
    atom_type: String,
    sti: f64,
    tv: TruthValueEntry,
}

#[derive(Serialize)]
struct TypeSummary {
    atom_type: String,
    count: usize,
}

#[derive(Serialize)]
struct TruthValueEntry {
    strength: f64,
    confidence: f64,
}

#[derive(Deserialize)]
struct TraceQuery {
    input: String,
}
