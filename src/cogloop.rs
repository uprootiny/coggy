//! The cognitive loop: PARSE → GROUND → ATTEND → INFER → REFLECT

use crate::atomspace::AtomSpace;
use crate::ecan::{self, EcanConfig};
use crate::parse;
use crate::pln;

pub struct TraceStep {
    pub phase: String,
    pub lines: Vec<String>,
}

pub struct CogLoopResult {
    pub new_atoms: usize,
    pub total_atoms: usize,
    pub turn: u32,
    pub inferences: usize,
    pub trace: Vec<TraceStep>,
}

pub fn run(space: &mut AtomSpace, input: &str, ecan_config: &EcanConfig) -> CogLoopResult {
    space.turn += 1;
    let turn = space.turn;
    let initial_size = space.size();
    let mut trace = Vec::new();

    // ── PARSE ──────────────────────────────────────────────
    let parsed = parse::parse_input(space, input);
    let mut parse_lines = vec![format!("{} atoms produced", parsed.new_count())];
    for pa in &parsed.atoms {
        let marker = if pa.is_new { "\u{2295}" } else { "\u{25cb}" };
        parse_lines.push(format!("{} {}", marker, pa.desc));
    }
    trace.push(TraceStep {
        phase: "PARSE \u{2192} NL\u{2192}Atomese".into(),
        lines: parse_lines,
    });

    // ── GROUND ─────────────────────────────────────────────
    let mut ground_lines = Vec::new();
    for pa in &parsed.atoms {
        if let Some(atom) = space.get(pa.id) {
            if atom.atom_type.is_node() {
                let incoming = space.get_incoming(pa.id);
                // Grounded if it has connections from the ontology (incoming links not from this parse)
                let ont_links: Vec<_> = incoming
                    .iter()
                    .filter(|&&lid| !parsed.all_ids().contains(&lid))
                    .collect();
                if !ont_links.is_empty() {
                    ground_lines.push(format!(
                        "\u{2295} ({}) GROUNDED \u{2014} {} ontology links",
                        pa.desc,
                        ont_links.len()
                    ));
                } else if pa.is_new {
                    ground_lines.push(format!(
                        "\u{25cb} ({}) NOT FOUND \u{2014} 0 links",
                        pa.desc
                    ));
                } else {
                    ground_lines.push(format!(
                        "\u{2295} ({}) EXISTING \u{2014} matched",
                        pa.desc
                    ));
                }
            }
        }
    }
    if ground_lines.is_empty() {
        ground_lines.push("no atoms to ground".into());
    }
    trace.push(TraceStep {
        phase: "GROUND \u{2192} ontology lookup".into(),
        lines: ground_lines,
    });

    // ── ATTEND ─────────────────────────────────────────────
    // Boost all referenced atoms (not just new ones) — they were mentioned
    let activated = parsed.all_ids();
    let sti_changes = ecan::spread_attention(space, &activated, ecan_config);

    let mut attend_lines = Vec::new();
    let mut sorted: Vec<_> = sti_changes
        .iter()
        .filter(|c| (c.new_val - c.old).abs() > 0.1)
        .collect();
    sorted.sort_by(|a, b| {
        b.new_val
            .partial_cmp(&a.new_val)
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    for c in sorted.iter().take(12) {
        let name = space.format_atom(c.id);
        if c.boosted() && c.old < 0.1 {
            attend_lines.push(format!("\u{2605} {}: STI 0\u{2192}{:.1}", name, c.new_val));
        } else if c.boosted() {
            attend_lines.push(format!(
                "\u{2605} {}: STI {:.1}\u{2192}{:.1}",
                name, c.old, c.new_val
            ));
        } else if c.decayed() {
            attend_lines.push(format!(
                "\u{2198} {}: STI {:.1}\u{2192}{:.1}",
                name, c.old, c.new_val
            ));
        }
    }
    if attend_lines.is_empty() {
        attend_lines.push("no attention changes".into());
    }
    trace.push(TraceStep {
        phase: "ATTEND \u{2192} STI spread".into(),
        lines: attend_lines,
    });

    // ── INFER ──────────────────────────────────────────────
    let inferences = pln::forward_chain(space, 2);
    let inf_count = inferences.len();

    let mut infer_lines = Vec::new();
    for inf in &inferences {
        let name = space.format_atom(inf.conclusion_id);
        let premises: Vec<String> = inf
            .premises
            .iter()
            .map(|&id| space.format_atom(id))
            .collect();
        infer_lines.push(format!(
            "\u{22a2} {} \u{2190} {} [{}] ({})",
            name,
            inf.rule,
            premises.join(", "),
            inf.tv
        ));
    }
    trace.push(TraceStep {
        phase: format!(
            "INFER \u{2192} PLN forward chain (depth 2) \u{2014} {} inferences",
            inf_count
        ),
        lines: infer_lines,
    });

    // ── REFLECT ────────────────────────────────────────────
    let new_count = space.size() - initial_size;
    let top = space.atoms_by_sti(1);
    let peak = if let Some(a) = top.first() {
        format!("  |  Peak STI: {}({:.1})", space.format_atom(a.id), a.av.sti)
    } else {
        String::new()
    };

    let reflect_lines = vec![format!(
        "New atoms: {}  |  Inferred: {}{}",
        new_count, inf_count, peak
    )];
    trace.push(TraceStep {
        phase: "REFLECT \u{2192} trace summary".into(),
        lines: reflect_lines,
    });

    CogLoopResult {
        new_atoms: new_count,
        total_atoms: space.size(),
        turn,
        inferences: inf_count,
        trace,
    }
}
