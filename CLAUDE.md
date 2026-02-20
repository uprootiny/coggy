# Coggy — Project Guide

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
- [x] Retro chat UI with trace rendering
- [ ] Squint-based reactive UI
- [ ] Force-directed atomspace graph
- [ ] Inference trace timeline

### Ops (deployment, scripts, CI)
Operational tooling.
- [x] coggyctl.sh — start/stop/restart/smoke/fleet
- [x] save-sessions.sh — tmux session capture/restore
- [x] 2-job CI pipeline (lint+test, smoke+artifacts)
- [x] Shellcheck on all scripts
- [ ] Release automation (tag + changelog)
- [ ] Health monitoring / alerting

## Conventions

**Naming**: kebab-case for functions and atoms. Keywords for internal keys. Strings for atom names in the hypergraph.

**Testing**: Three test suites, all run with `bb test/coggy/<suite>.clj`:
- `atomspace_test.clj` — core substrate + agent API + rescue
- `bench_test.clj` — benchmarks + challenge drills
- `domain_test.clj` — domain pack validation

**Commits**: Conventional style — `feat:`, `fix:`, `test:`, `chore:`, `ci:`, `docs:`. One concern per commit.

**Ports**: Random high ports. Default COGGY_PORT=8420, configurable via env.

**Truth values**: `(stv strength confidence)` where both are [0,1]. Strength = degree of belief, confidence = weight of evidence.

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
