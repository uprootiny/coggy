use std::io::{self, BufRead, Write};

use coggy::atomspace::AtomSpace;
use coggy::cogloop;
use coggy::ecan::EcanConfig;
use coggy::ontology;
use coggy::pln;
use coggy::tikkun;

fn main() {
    let mut space = AtomSpace::new();
    let ecan_config = EcanConfig::default();

    let loaded = ontology::load_base_ontology(&mut space);

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

    let stdin = io::stdin();
    let mut stdout = io::stdout();

    loop {
        print!("coggy [{}]> ", space.turn);
        stdout.flush().unwrap();

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
            ":help" | ":h" => print_help(),
            ":atoms" | ":a" => print_atoms(&space),
            ":focus" | ":f" => print_focus(&space),
            ":types" | ":t" => print_types(&space),
            ":infer" | ":i" => run_infer(&mut space),
            ":tikkun" | ":tk" => run_tikkun(&space),
            input => {
                let result = cogloop::run(&mut space, input, &ecan_config);
                print_trace(&result);
            }
        }
    }

    println!("Coggy shutting down. {} atoms in AtomSpace.", space.size());
}

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
        let premises: Vec<String> = inf.premises.iter().map(|&id| space.format_atom(id)).collect();
        println!("  \u{22a2} {} ({}) [{}]", name, inf.tv, premises.join(" + "));
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
