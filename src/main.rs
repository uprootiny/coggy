use std::io::{self, BufRead, Write};

use coggy::atomspace::AtomSpace;
use coggy::cogloop;
use coggy::ecan::EcanConfig;
use coggy::ontology;
use coggy::pln;
use coggy::tikkun;
use serde_json::json;

fn main() {
    let json_mode = std::env::args().any(|a| a == "--json");

    let mut space = AtomSpace::new();
    let ecan_config = EcanConfig::default();

    let loaded = ontology::load_base_ontology(&mut space);

    if json_mode {
        println!(
            "{}",
            json!({
                "event": "init",
                "atoms_loaded": loaded,
                "total_atoms": space.size(),
            })
        );
    } else {
        println!("\u{25c8} COGGY \u{2014} Cognitive Architecture (Rust)");
        println!("  {} atoms loaded from base ontology.", loaded);
        println!("  AtomSpace: {} total atoms", space.size());
        println!();
        println!("Commands:");
        println!("  <text>        \u{2014} run cognitive loop on input");
        println!("  :atoms        \u{2014} show all atoms");
        println!("  :focus        \u{2014} show attention focus (top STI)");
        println!("  :types        \u{2014} show atom type counts");
        println!("  :infer        \u{2014} run PLN forward chain manually");
        println!("  :tikkun       \u{2014} run self-repair diagnostics");
        println!("  :help         \u{2014} show this help");
        println!("  :quit         \u{2014} exit");
        println!();
    }

    let stdin = io::stdin();
    let mut stdout = io::stdout();

    loop {
        if !json_mode {
            print!("coggy [{}]> ", space.turn);
            stdout.flush().unwrap();
        }

        let mut line = String::new();
        if stdin.lock().read_line(&mut line).unwrap() == 0 {
            break;
        }
        let line = line.trim();
        if line.is_empty() {
            continue;
        }

        match line {
            ":quit" | ":q" | ":exit" => break,
            ":help" | ":h" => {
                if json_mode {
                    println!(
                        "{}",
                        json!({"event": "help", "commands": [
                            ":atoms", ":focus", ":types", ":infer", ":tikkun", ":quit"
                        ]})
                    );
                } else {
                    print_help();
                }
            }
            ":atoms" | ":a" => {
                if json_mode {
                    print_atoms_json(&space);
                } else {
                    print_atoms(&space);
                }
            }
            ":focus" | ":f" => {
                if json_mode {
                    print_focus_json(&space);
                } else {
                    print_focus(&space);
                }
            }
            ":types" | ":t" => {
                if json_mode {
                    print_types_json(&space);
                } else {
                    print_types(&space);
                }
            }
            ":infer" | ":i" => {
                if json_mode {
                    run_infer_json(&mut space);
                } else {
                    run_infer(&mut space);
                }
            }
            ":tikkun" | ":tk" => {
                if json_mode {
                    run_tikkun_json(&space);
                } else {
                    run_tikkun(&space);
                }
            }
            input => {
                let result = cogloop::run(&mut space, input, &ecan_config);
                if json_mode {
                    print_trace_json(&result, &space);
                } else {
                    print_trace(&result);
                }
            }
        }
    }

    if json_mode {
        println!(
            "{}",
            json!({"event": "shutdown", "total_atoms": space.size()})
        );
    } else {
        println!("Coggy shutting down. {} atoms in AtomSpace.", space.size());
    }
}

// ── Human-readable output ──────────────────────────────────

fn print_trace(r: &cogloop::CogLoopResult) {
    println!(
        "+{} atoms \u{2502} {} total \u{2502} turn {}",
        r.new_atoms, r.total_atoms, r.turn
    );
    println!("\u{2500}\u{2500} COGGY TRACE \u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}");
    for step in &r.trace {
        println!("\u{2502} {}", step.phase);
        for line in &step.lines {
            println!("\u{2502}   {}", line);
        }
    }
    println!("\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}\u{2500}");
}

fn print_atoms(space: &AtomSpace) {
    println!("\u{2295} ATOMS ({} total)", space.size());
    for atom in space.all_atoms_sorted() {
        let name = space.format_atom(atom.id);
        if atom.av.sti > 0.01 {
            println!("  {} {} av(sti:{:.1})", name, atom.tv, atom.av.sti);
        } else {
            println!("  {} {}", name, atom.tv);
        }
    }
}

fn print_focus(space: &AtomSpace) {
    println!("\u{2605} FOCUS (top STI atoms)");
    let top = space.atoms_by_sti(15);
    if top.is_empty() {
        println!("  (no atoms with STI > 0)");
        return;
    }
    for atom in top {
        println!(
            "  \u{2605} {} STI={:.1} ({})",
            space.format_atom(atom.id),
            atom.av.sti,
            atom.tv
        );
    }
}

fn print_types(space: &AtomSpace) {
    println!("Atom types:");
    for (t, count) in space.types_present() {
        println!("  {}: {}", t, count);
    }
}

fn run_infer(space: &mut AtomSpace) {
    println!("Running PLN forward chain (depth 3)...");
    let inferences = pln::forward_chain(space, 3);
    println!("\u{22a2} {} inferences produced", inferences.len());
    for inf in &inferences {
        let name = space.format_atom(inf.conclusion_id);
        let premises: Vec<String> = inf
            .premises
            .iter()
            .map(|&id| space.format_atom(id))
            .collect();
        println!(
            "  \u{22a2} {} ({}) [{}]",
            name,
            inf.tv,
            premises.join(" + ")
        );
    }
}

fn run_tikkun(space: &AtomSpace) {
    println!("Running tikkun diagnostics...");
    let report = tikkun::run_tikkun(space);
    for check in &report.checks {
        let sym = if check.passed { "\u{2713}" } else { "\u{2717}" };
        let detail = check.detail.as_deref().unwrap_or("");
        println!("  {} {} {}", sym, check.name, detail);
    }
    if report.all_healthy {
        println!("\u{2726} Tikkun: all checks healthy \u{2713}");
    } else {
        println!("\u{2726} Tikkun: ISSUES DETECTED");
    }
}

fn print_help() {
    println!("\u{25c8} Coggy \u{2014} Cognitive Architecture");
    println!();
    println!("Assertions:");
    println!("  \"cat is-a mammal\"   \u{2014} assert inheritance link");
    println!("  \"cat is a pet\"      \u{2014} assert inheritance link");
    println!("  \"cat likes fish\"    \u{2014} assert evaluation link");
    println!();
    println!("Questions:");
    println!("  \"what is cat\"       \u{2014} query (creates evaluation atoms)");
    println!("  \"what can you do\"   \u{2014} query");
    println!();
    println!("Commands:");
    println!("  :atoms   \u{2014} show all atoms");
    println!("  :focus   \u{2014} show attention focus (top STI)");
    println!("  :types   \u{2014} show type counts");
    println!("  :infer   \u{2014} run PLN forward chain");
    println!("  :tikkun  \u{2014} run diagnostics");
    println!("  :quit    \u{2014} exit");
}

// ── JSON output ────────────────────────────────────────────

fn print_trace_json(r: &cogloop::CogLoopResult, space: &AtomSpace) {
    let trace: Vec<serde_json::Value> = r
        .trace
        .iter()
        .map(|s| {
            json!({
                "phase": s.phase,
                "lines": s.lines,
            })
        })
        .collect();

    let focus: Vec<serde_json::Value> = space
        .atoms_by_sti(10)
        .iter()
        .map(|a| {
            json!({
                "atom": space.format_atom(a.id),
                "sti": (a.av.sti * 10.0).round() / 10.0,
                "tv": { "s": a.tv.strength, "c": a.tv.confidence },
            })
        })
        .collect();

    println!(
        "{}",
        json!({
            "event": "trace",
            "turn": r.turn,
            "new_atoms": r.new_atoms,
            "total_atoms": r.total_atoms,
            "inferences": r.inferences,
            "trace": trace,
            "focus": focus,
        })
    );
}

fn print_atoms_json(space: &AtomSpace) {
    let atoms: Vec<serde_json::Value> = space
        .all_atoms_sorted()
        .iter()
        .map(|a| {
            json!({
                "id": a.id,
                "type": format!("{}", a.atom_type),
                "name": a.name,
                "outgoing": a.outgoing,
                "tv": { "s": a.tv.strength, "c": a.tv.confidence },
                "sti": (a.av.sti * 10.0).round() / 10.0,
            })
        })
        .collect();
    println!(
        "{}",
        json!({"event": "atoms", "count": space.size(), "atoms": atoms})
    );
}

fn print_focus_json(space: &AtomSpace) {
    let focus: Vec<serde_json::Value> = space
        .atoms_by_sti(15)
        .iter()
        .map(|a| {
            json!({
                "atom": space.format_atom(a.id),
                "sti": (a.av.sti * 10.0).round() / 10.0,
                "tv": { "s": a.tv.strength, "c": a.tv.confidence },
            })
        })
        .collect();
    println!("{}", json!({"event": "focus", "atoms": focus}));
}

fn print_types_json(space: &AtomSpace) {
    let types: serde_json::Value = space
        .types_present()
        .iter()
        .map(|(t, c)| (format!("{}", t), json!(c)))
        .collect();
    println!("{}", json!({"event": "types", "types": types}));
}

fn run_infer_json(space: &mut AtomSpace) {
    let inferences = pln::forward_chain(space, 3);
    let inf_json: Vec<serde_json::Value> = inferences
        .iter()
        .map(|inf| {
            json!({
                "rule": inf.rule,
                "conclusion": space.format_atom(inf.conclusion_id),
                "premises": inf.premises.iter().map(|&id| space.format_atom(id)).collect::<Vec<_>>(),
                "tv": { "s": inf.tv.strength, "c": inf.tv.confidence },
            })
        })
        .collect();
    println!(
        "{}",
        json!({"event": "infer", "count": inferences.len(), "inferences": inf_json})
    );
}

fn run_tikkun_json(space: &AtomSpace) {
    let report = tikkun::run_tikkun(space);
    let checks: Vec<serde_json::Value> = report
        .checks
        .iter()
        .map(|c| {
            json!({
                "name": c.name,
                "passed": c.passed,
                "detail": c.detail,
            })
        })
        .collect();
    println!(
        "{}",
        json!({"event": "tikkun", "all_healthy": report.all_healthy, "checks": checks})
    );
}
