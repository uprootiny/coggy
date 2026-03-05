# Coggy — Developer Guide

Cognitive architecture in Rust: AtomSpace hypergraph + PLN inference + ECAN attention.

## Build & Test

```bash
cargo build          # compile
cargo test           # run all unit tests
cargo run            # interactive REPL
cargo run -- --json  # machine-readable JSON output
```

## Architecture

The cognitive loop runs on every input: **PARSE → GROUND → ATTEND → INFER → REFLECT**.

```
  NL input
     │
     ▼
  ┌─────────┐    ┌────────────┐
  │  PARSE   │───▶│  GROUND    │  match parsed atoms against ontology
  └─────────┘    └────────────┘
                       │
                       ▼
                 ┌────────────┐
                 │  ATTEND    │  ECAN: boost activated, spread STI, decay old
                 └────────────┘
                       │
                       ▼
                 ┌────────────┐
                 │  INFER     │  PLN forward chain (deduction rule)
                 └────────────┘
                       │
                       ▼
                 ┌────────────┐
                 │  REFLECT   │  summarize: new atoms, inferences, peak STI
                 └────────────┘
```

## Module Map

| File              | Purpose                                           |
|-------------------|---------------------------------------------------|
| `src/atom.rs`     | Core types: `Atom`, `TruthValue`, `AttentionValue`, `AtomType` |
| `src/atomspace.rs`| Hypergraph store with indexed lookups, incoming sets |
| `src/ecan.rs`     | Economic Attention Network — STI boost, spread, decay, rent |
| `src/pln.rs`      | Probabilistic Logic Networks — forward-chaining deduction |
| `src/parse.rs`    | NL→Atomese rule-based parser                       |
| `src/ontology.rs` | Base ontology loader (biological taxonomy)          |
| `src/tikkun.rs`   | Self-repair diagnostics (5 health checks)           |
| `src/cogloop.rs`  | Orchestrates the cognitive loop, produces traces    |
| `src/main.rs`     | REPL with human-readable and `--json` output modes  |
| `src/lib.rs`      | Module declarations                                |

## Key Data Types

- **TruthValue** `{strength, confidence}` — probabilistic truth, both in [0,1]
- **AttentionValue** `{sti, lti}` — short-term and long-term importance
- **AtomId** `u64` — monotonic atom identifier
- **Atom** — node (has name) or link (has outgoing set), plus TV and AV
- **AtomType** — `ConceptNode`, `PredicateNode`, `InheritanceLink`, `EvaluationLink`, `ListLink`

## PLN Deduction Formula

```
Given: A→B (s_ab, c_ab) and B→C (s_bc, c_bc)
Derive: A→C
  strength   = s_ab * s_bc
  confidence = min(c_ab, c_bc) * 0.9
```

Confidence degrades through longer chains — this is by design.

## ECAN Attention

- **Boost**: activated atoms get initial STI (concepts ~38, predicates ~20, links ~34–40)
- **Spread**: links spread STI to outgoing atoms; all atoms spread weakly to incoming links
- **Decay**: inactive atoms lose STI each turn (factor 0.7, minus 0.5 rent)
- **Focus**: top-STI atoms represent current "attention" — viewable with `:focus`

## REPL Commands

- `<text>` — run cognitive loop
- `:atoms` — dump all atoms with TVs
- `:focus` — show attention heap
- `:types` — atom type counts
- `:infer` — run PLN manually
- `:tikkun` — health diagnostics
- `:help` — usage
- `:quit` — exit

## Conventions

- No external dependencies except `serde_json` (for `--json` mode)
- Tests live in `#[cfg(test)] mod tests` inside each module
- Atom IDs are stable monotonic u64s (1, 2, 3, ...)
- The ontology contains only direct inheritance links; PLN derives the transitive closure
