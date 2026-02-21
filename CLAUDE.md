# Coggy — Project Guide

## Behavioral Encoding

When working on this repo, Claude Code should think like coggy thinks. This means:

**Ground before generating.** Before writing code, check what already exists. Query the module map. Don't create new abstractions when the atomspace, attention, or semantic modules already have what you need. Grounding rate matters — every change should latch onto existing structure.

**Name the types.** When reasoning about changes, be explicit about what kind of thing you're touching:
- Atoms (ConceptNode, PredicateNode) — the knowledge substrate
- Links (InheritanceLink, ImplicationLink, SimilarityLink, EvaluationLink) — the relations
- Truth values (stv strength confidence) — the epistemological stance
- Attention (STI/LTI) — what's foregrounded, what's fading
- Rescue strategies — what to do when things fail

**Show uncertainty.** If a change might break something, say so with explicit confidence: "high confidence this is safe" vs "low confidence — needs testing." Don't hide uncertainty behind confident prose.

**Commit atomically.** One concern per commit. `feat:` for new capabilities, `fix:` for repairs, `test:` for coverage, `docs:` for documentation, `ci:` for pipeline changes. Each commit should leave tests passing.

**Test before claiming victory.** Run `bb test/coggy/atomspace_test.clj && bb test/coggy/domain_test.clj && bb test/coggy/bench_test.clj` after changes. 131 tests, 485 assertions. All must pass.

**Preserve invariants.** The GUARANTEES.md documents what holds. Don't break: TV revision monotonicity, link deduplication, spread activation conservation, boot determinism, domain seeding. If you must change an invariant, update GUARANTEES.md.

**Prefer data over control.** Domain packs are data, not code. Rescue strategies dispatch by type. Configuration is maps, not nested if/else. When adding behavior, ask: can this be a data structure instead?

**Remove, don't add.** Coggy follows suckless philosophy. If a change can be done by removing lines instead of adding them, do that. If a new function can be avoided by composing existing ones, compose. Every line must earn its place.

## What This Is

Coggy is an inspectable ontology-first reasoning harness. It maintains a hypergraph knowledge store (AtomSpace), an economic attention mechanism (ECAN), and a semantic pipeline that grounds LLM output into auditable structure. Other agents can observe, query, and stimulate the shared knowledge substrate via HTTP.

Built in Babashka. No JVM. No build tool. Just `bb`.

## Architecture

```
                  +-----------+
 agents ------>   |  web.clj  |  <------ browser
                  +-----+-----+
                        |
              +---------+---------+
              |                   |
        semantic.clj         repl.clj
        (pipeline)           (session state)
              |                   |
    +---------+---------+         |
    |         |         |         |
atomspace  attention  domain    boot.clj
   .clj      .clj      .clj   (seed ritual)
    |         |
    +---------+
     (shared substrate)
```

## Module Map

| File | Role |
|------|------|
| `atomspace.clj` | Hypergraph knowledge store. Atoms, links, truth values, TV revision, content-based link dedup. |
| `attention.clj` | ECAN-lite. STI/LTI per atom, stimulation, decay, spread activation through link graph, focus set. |
| `semantic.clj` | Semantic pipeline. Extract/normalize/ground/commit/spread/rescue. Agent observation adapter. |
| `domain.clj` | Domain packs. 9 structured knowledge seeds with concepts, typed relations, strategies, prompts. |
| `boot.clj` | Boot ritual. Seeds core ontology into fresh atomspace + attention bank. |
| `web.clj` | HTTP server. Chat UI, agent API (observe/query/stimulate/focus/atoms), state endpoints. |
| `repl.clj` | Session state. Turn tracking, snapshot save/restore, event log. |
| `llm.clj` | OpenRouter LLM client. Model selection, scoring, token tracking. |
| `trace.clj` | Trace renderer. Formats the visible reasoning skeleton (PARSE/GROUND/ATTEND/INFER/REFLECT). |
| `tui.clj` | Terminal UI components. Box drawing, colors, layout helpers. |
| `bench.clj` | Benchmarks and challenge drills. Measures grounding rate, attention health, pipeline speed. |
| `main.clj` | Entry point. Boots coggy, enters the REPL loop. |

## Key Functions to Reuse

Before writing new code, check these existing functions:

**atomspace.clj**: `make-space`, `add-atom!`, `add-link!`, `get-atom`, `query-links`, `space-stats`, `tv-revise`, `link-key`, `concept`, `predicate`, `inheritance`, `implication`, `similarity`, `evaluation`, `stv`

**attention.clj**: `make-bank`, `stimulate!`, `update-focus!`, `focus-atoms`, `spread-activation!`, `in-focus?`, `fund-balance`, `link-atom-keys`, `link-source-key`

**semantic.clj**: `process-semantic!`, `commit-observation!`, `query-atoms`, `metrics-summary`, `normalize-semantic`, `ground-concepts`, `ground-relations`, `diagnose-failure`, `trigger-rescue!`

**domain.clj**: `available-domains`, `get-domain`, `domain-brief`, `seed-domain!`

## Subprojects

### Core (atomspace, attention, semantic)
The knowledge substrate. Everything else builds on this.
- [x] TV revision (PLN confidence-weighted merge)
- [x] Content-based link deduplication
- [x] Spread activation through all link types
- [x] Rescue strategies (grounding vacuum, ontology miss, contradiction, budget exhaustion)
- [ ] Pattern matching / unification
- [ ] Backward chaining inference
- [ ] Type-level atom constraints

### Agent API (web endpoints, coggy-client)
HTTP interface for cross-agent knowledge sharing.
- [x] POST /api/observe — submit semantic observations
- [x] POST /api/query — look up atoms + links + attention
- [x] POST /api/stimulate — nudge attention
- [x] GET /api/focus — current attentional focus
- [x] GET /api/atoms/:name — single atom lookup
- [x] coggy-client shell script
- [ ] WebSocket event stream
- [ ] Agent identity / authentication

### Domains (domain packs, seeding)
Structured knowledge seeds for expert reasoning.
- [x] 9 packs: legal, ibid-legal, forecast, bio, unix, research, balance, study, accountability
- [x] Parametric validation (all domains tested)
- [ ] Domain composition (activate multiple simultaneously)
- [ ] Custom domain definition via API

### Web UI (visualization, interaction)
Browser-based interaction surface.
- [x] Skeuomorphic chat UI with live event ticker
- [x] Canvas view with force-directed attention bubbles
- [ ] Force-directed atomspace graph (link-aware)
- [ ] Inference trace timeline

### Ops (deployment, scripts, CI)
Operational tooling.
- [x] coggyctl.sh — start/stop/restart/smoke/fleet
- [x] save-sessions.sh — tmux session capture/restore
- [x] 2-job CI pipeline (lint+test, smoke+artifacts)
- [x] Shellcheck on all scripts
- [x] v0.1.0 tagged release with CHANGELOG
- [ ] Release automation
- [ ] Health monitoring / alerting

## Conventions

**Naming**: kebab-case for functions and atoms. Keywords for internal keys. Strings for atom names in the hypergraph.

**Testing**: Three test suites, all run with `bb test/coggy/<suite>.clj`:
- `atomspace_test.clj` — core substrate + agent API + rescue (83 tests)
- `bench_test.clj` — benchmarks + challenge drills (36 tests)
- `domain_test.clj` — domain pack validation (12 tests)

**Commits**: Conventional style — `feat:`, `fix:`, `test:`, `chore:`, `ci:`, `docs:`. One concern per commit.

**Ports**: Random high ports. Default COGGY_PORT=8420, configurable via env. Currently live at 48420.

**Truth values**: `(stv strength confidence)` where both are [0,1]. Strength = degree of belief, confidence = weight of evidence.

**Link types**: `:inherits` (InheritanceLink), `:causes` (ImplicationLink), `:resembles` (SimilarityLink). Links are content-addressed — duplicates get TV revised.

## Dependencies

- `bb` (Babashka) — the only runtime
- `org.httpkit/http-kit` — HTTP server
- `cheshire` — JSON encoding
- `babashka/http-client` — HTTP client (for LLM calls)

No JVM. No npm. No build tool beyond bb.edn.

## Development

```bash
# Run all tests
bb test/coggy/atomspace_test.clj
bb test/coggy/domain_test.clj
bb test/coggy/bench_test.clj

# Start server
./coggy start

# Smoke test
./coggy smoke

# Agent API
./scripts/coggy-client observe '{"concepts":["x","y"],"relations":[],"confidence":0.8}'
./scripts/coggy-client focus
./scripts/coggy-client health
```

## Reference Documents

- `GUARANTEES.md` — what's tested, what invariants hold (versioned)
- `CHANGELOG.md` — conventional changelog
- `DESIGN.md` — system design, process history, v0.2.0 sketch
- `README.md` — operational quick start
