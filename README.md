# Coggy

A cognitive architecture in Rust, inspired by OpenCog. Implements a hypergraph knowledge store (AtomSpace), probabilistic reasoning (PLN), and attention allocation (ECAN) in ~900 lines with zero unsafe code.

## What It Does

Coggy runs a **cognitive loop** on every input:

1. **PARSE** — decomposes natural language into typed atoms (concepts, predicates, links)
2. **GROUND** — checks which atoms exist in the knowledge base vs. are novel
3. **ATTEND** — spreads short-term importance through the hypergraph (ECAN)
4. **INFER** — runs probabilistic forward-chaining deduction (PLN)
5. **REFLECT** — summarizes what happened: new atoms, inferences, peak attention

Every atom carries a **truth value** (strength, confidence) and an **attention value** (STI). Inference degrades confidence through chains. Attention decays over time. The system is epistemically honest — it shows you exactly what it knows and what it derived.

## Quick Start

```bash
cargo build
cargo run
```

```
◈ COGGY — Cognitive Architecture (Rust)
  37 atoms loaded from base ontology.

coggy [0]> cat is-a pet
+2 atoms │ 39 total │ turn 1
── COGGY TRACE ─────────────────────────────────────────────
│ PARSE → NL→Atomese
│   2 atoms produced
│ GROUND → ontology lookup
│   ⊕ (ConceptNode "cat") GROUNDED — 1 ontology links
│   ○ (ConceptNode "pet") NOT FOUND — 0 links
│ ATTEND → STI spread
│   ★ ConceptNode:"cat": STI 0→43.1
│   ★ ConceptNode:"pet": STI 0→43.1
│ INFER → PLN forward chain (depth 2) — 29 inferences
│   ⊢ InheritanceLink:[cat→animal] ← deduction [...]
│   ...
│ REFLECT → trace summary
│   New atoms: 31  |  Inferred: 29  |  Peak STI: ConceptNode:"cat"(43.1)
────────────────────────────────────────────────────────────

coggy [1]> penguin is-a bird
│ INFER → PLN forward chain (depth 2) — 3 inferences
│   ⊢ InheritanceLink:[penguin→animal] ← deduction
│   ⊢ InheritanceLink:[penguin→living-thing] ← deduction
│   ⊢ InheritanceLink:[penguin→thing] ← deduction
```

## JSON Mode

For machine-readable output (piping to other tools, web UIs):

```bash
echo "cat is-a pet" | cargo run -- --json
```

Each line is a self-contained JSON object with `event` type, trace data, focus atoms, and truth values.

## Commands

| Command    | Description                          |
|------------|--------------------------------------|
| `<text>`   | Run cognitive loop on input          |
| `:atoms`   | Show all atoms with truth values     |
| `:focus`   | Show attention focus (top STI atoms) |
| `:types`   | Show atom type counts                |
| `:infer`   | Run PLN forward chain manually       |
| `:tikkun`  | Run self-repair diagnostics          |
| `:help`    | Show help                            |
| `:quit`    | Exit                                 |

## Input Patterns

```
cat is-a mammal        →  InheritanceLink [cat → mammal]
penguin is a bird      →  InheritanceLink [penguin → bird]
cat likes fish         →  EvaluationLink [likes → (cat, fish)]
what is that           →  EvaluationLink [is → (what, that)]
what can you do        →  EvaluationLink [can-you → (what, do)]
```

## Testing

```bash
cargo test
```

Unit tests cover all core modules: atomspace (10 tests), PLN (7 tests), ECAN (6 tests), parser (11 tests), and tikkun diagnostics (6 tests).

## Architecture

See [CLAUDE.md](CLAUDE.md) for the full developer guide, module map, data types, and PLN/ECAN formulas.

## Web landing page & API

The [`static/index.html`](static/index.html) landing page lives alongside a small web service. Run it with:

```bash
COGGY_PORT=8421 cargo run --bin web
```

It serves the Coggy canvas at `http://localhost:8421` (the live host uses `http://173.212.203.211:8421`) and exposes health, focus, feed, and trace endpoints described in [`docs/api.md`](docs/api.md). Use `scripts/run-web.sh` to build and launch the server inside tmux or another process manager.

For multi-port demos, use `scripts/run-multi-web.sh` so you can bring up 8421, 8431, 8451, etc., simultaneously.

## Live demonstration

For a quick demo that proves the landing page, API, and focus traces work together, run:

```
PORT=8431 TRACE_INPUT="penguin is-a bird" ./scripts/demo-run.sh
```

The script hits `/api/health`, `/api/trace`, and `/api/focus`, pretty-prints the JSON using `jq`, and writes a log at `logs/demo-8431.log`. See [`docs/demo.md`](docs/demo.md) for the step-by-step narrative, next actions, and how to fold the log into breadcrumbs or Claude prompts.

## Deployment & CI/CD

The `.github/workflows/ci.yml` pipeline checks formatting, lints, runs tests, and compiles release binaries for both `coggy` and `web`. The landing page and binaries are uploaded as an artifact named `coggy-releases`, which you can fetch when deploying onto remote hosts.

Alternates (e.g., staging) can run on different ports by setting `COGGY_PORT` before launching the script:

```bash
COGGY_PORT=8431 ./scripts/run-web.sh
```

Mount the artifact on your server, point tmux status lines to the new port, and tie `/api/health` into your monitoring stack so clickable deployment URLs stay truthful.

## Knowledge middleware & data feeds

The web server shares the same AtomSpace and Ecan configuration as the CLI, so `/api/feed` and `/api/trace` provide live insights into the knowledge base. That makes it easy to embed Coggy data into dashboards, Claude prompts, or other automation that needs to reason over the same traces you work with locally. Document experiments by pasting traces into breadcrumbs/prompts and keep the UX contract alive.

## Architectural coherence

New UX/viz, deployment, and monitoring additions should stay aligned with the decisions captured in [`docs/architecture-fit.md`](docs/architecture-fit.md). That document maps the stateful canvas, API surface, high-port deployments, monitoring safeguards (tripwires, tmux badges), and breadcrumb rituals into one cohesive story so future work doesn’t drift.

## Dependencies

- `serde_json` — JSON output for `--json` mode

That's it. No runtime, no framework, no unsafe.
