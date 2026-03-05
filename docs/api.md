# Coggy Web API

The Coggy web binary (`cargo run --bin web`) combines the landing page with a lightweight knowledge middleware that keeps the AtomSpace hypergraph accessible to dashboards, Claude agents, and automation pipelines.

## Running locally

```bash
COGGY_PORT=8421 cargo run --bin web
```

Point your browser to `http://localhost:8421` (or `http://173.212.203.211:8421` on the live host) to see the Coggy landing page. The API is available alongside the page under `/api`.

## Endpoints

| Path | Description |
|------|-------------|
| `/` | Serves the landing page from `static/index.html` (same content as `docs/api.md`). |
| `/api/health` | Returns `{ status: "ok", atoms, turn }` so deployment health checks can be wired into monitoring dashboards. |
| `/api/focus` | Reports the top STI atoms with their truth values to feed attention-centric views. |
| `/api/feed` | Combines focus and type counts; ideal for live dashboards that visualize the AtomSpace state. |
| `/api/trace?input=...` | Runs a single Coggy cognitive loop, returns trace, inference count, and updated focus. Accepts free-text input. |

### Sample trace request

```bash
curl 'http://localhost:8421/api/trace?input=cat%20is-a%20mammal'
```

Each response is JSON that documents the PARSE → GROUND → ATTEND → INFER → REFLECT steps, making it easy to archive experiments or surface them in a UI.

## Knowledge base middleware

The middleware sits between the CLI and a browser/dashboards by:

1. Sharing the same AtomSpace that the CLI uses (`AtomSpace::new()` plus `ontology::load_base_ontology`).
2. Productizing focus and type stats through `/api/focus` and `/api/feed`.
3. Accepting declarative inputs via `/api/trace` so other services can inject hypotheses, monitor inference counts, and retrieve traces.

Use the responses as a data feed in your dashboards, Claude prompts, or any automation that needs a view into the knowledge base.

