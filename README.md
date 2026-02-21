# Coggy

An inspectable ontology-first reasoning harness with a shared knowledge substrate.

Coggy maintains a hypergraph knowledge store (AtomSpace), an economic attention mechanism (ECAN), and a semantic pipeline that grounds LLM output into auditable structure. Other agents push observations, query knowledge, and stimulate attention via HTTP — no LLM roundtrip needed.

**v0.1.0** | 131 tests | 485 assertions | 12 modules | 9 domain packs

## Live

- **UI**: http://173.212.203.211:48420
- **Canvas**: http://173.212.203.211:48420/canvas
- **Health**: http://173.212.203.211:48420/health

## Quick Start

```bash
# Run tests
bb test/coggy/atomspace_test.clj
bb test/coggy/domain_test.clj
bb test/coggy/bench_test.clj

# Start server
COGGY_PORT=48420 ./coggy start

# Check health
curl -sS http://localhost:48420/health
```

## Agent API

Any agent can interact with coggy's knowledge substrate directly:

```bash
# Push observations (concepts + relations + confidence)
./scripts/coggy-client observe \
  '{"concepts":["x","y"],"relations":[{"type":"inherits","a":"x","b":"y"}],"confidence":0.8}'

# Query what coggy knows
./scripts/coggy-client query '{"concepts":["x"]}'

# Boost attention on concepts
./scripts/coggy-client stimulate '{"atoms":{"x":15.0}}'

# Read attentional focus
./scripts/coggy-client focus

# Look up a single atom
./scripts/coggy-client atom coggy
```

Or use curl directly:

```bash
# Observe
curl -X POST http://localhost:48420/api/observe \
  -H 'Content-Type: application/json' \
  -d '{"concepts":["alpha","beta"],"relations":[],"confidence":0.7,"source":"my-agent"}'

# Query
curl -X POST http://localhost:48420/api/query \
  -H 'Content-Type: application/json' \
  -d '{"concepts":["alpha"],"include_links":true}'

# Focus
curl http://localhost:48420/api/focus

# Atom lookup
curl http://localhost:48420/api/atoms/alpha
```

## Domain Packs

Seed expert knowledge into the atomspace:

| Domain | Concepts | Relations | Focus |
|--------|----------|-----------|-------|
| legal | 13 | 5 | jurisdiction, precedent, burden-of-proof |
| ibid-legal | 14 | 5 | IRAC chains, citation provenance |
| forecast | 10 | 4 | base rates, calibration, resolution criteria |
| bio | 10 | 5 | plasmid design, assay context, provenance |
| unix | 16 | 8 | processes, services, config-as-data |
| research | 15 | 8 | hypothesis-experiment-artifact cycles |
| balance | 16 | 8 | energy, capacity, load vs recovery |
| study | 16 | 8 | prerequisites, spaced review, confusion-as-signal |
| accountability | 16 | 8 | commitment tracking, reconciliation cycles |

Activate via API:

```bash
curl -X POST http://localhost:48420/api/domain \
  -H 'Content-Type: application/json' \
  -d '{"domain":"legal"}'
```

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

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | /health | Health check |
| GET | / | Chat UI |
| GET | /canvas | Attention canvas |
| GET | /api/state | Full state snapshot |
| GET | /api/metrics | Semantic health metrics |
| GET | /api/events | Recent event log |
| GET | /api/focus | Attentional focus (top-N by STI) |
| GET | /api/atoms/:name | Single atom + TV + links |
| POST | /api/observe | Agent semantic observation |
| POST | /api/query | Query atoms + links + attention |
| POST | /api/stimulate | Nudge attention on atoms |
| POST | /api/chat | Chat message (LLM roundtrip) |
| POST | /api/domain | Activate domain pack |
| POST | /api/boot | Re-seed core ontology |

## Snapshots

```bash
# Dump versioned snapshot
curl -X POST http://localhost:48420/api/state/dump \
  -H 'Content-Type: application/json' -d '{"mode":"versioned"}'

# List snapshots
curl http://localhost:48420/api/state/snapshots

# Load latest
curl -X POST http://localhost:48420/api/state/load \
  -H 'Content-Type: application/json' -d '{"latest":true}'
```

## Operations

```bash
./coggy start       # start server
./coggy stop        # stop server
./coggy restart     # restart
./coggy status      # check status
./coggy smoke       # smoke test
./coggy fleet       # fleet status
./coggy logs        # view logs
```

## Documentation

- [CLAUDE.md](CLAUDE.md) — project guide + behavioral encoding
- [DESIGN.md](DESIGN.md) — system design + v0.2.0 roadmap
- [GUARANTEES.md](GUARANTEES.md) — test coverage + invariants
- [CHANGELOG.md](CHANGELOG.md) — version history
