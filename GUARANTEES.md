# Coggy Guarantee Surface — v0.1.0

What's tested, what invariants hold, what's not yet covered.

## Test Suites

| Suite | Tests | Assertions | Covers |
|-------|-------|------------|--------|
| atomspace_test | 83 | 191 | Atoms, links, TV, TV revision, link dedup, attention, spread activation, semantic pipeline, rescue strategies, agent API |
| bench_test | 36 | 187 | Grounding rate, attention health, pipeline speed, challenge drills |
| domain_test | 12 | 107 | All 9 domain packs: seeding, atomspace population, attention stimulation, prompt validation |
| **Total** | **131** | **485** | |

All tests run in < 1s with `bb`.

## Invariants

**TV Revision**: Merging two truth values produces confidence >= max(c1, c2). Strength is weighted by confidence. Implemented as PLN revision formula.

**Link Deduplication**: Links are content-addressed via `link-key`. Same link added twice gets TV revised, not duplicated. Key includes type + ordered/unordered endpoints.

**Spread Activation**: STI flows from source atom through all connected links to all atoms in those links except the source. No self-stimulation.

**Domain Packs**: All 9 domains seed successfully into a fresh atomspace. Each has >= 10 concepts, >= 4 relations, a substantive prompt (> 50 chars), and named strategies.

**Rescue Strategies**: 5 failure types are diagnosed (grounding-vacuum, budget-exhausted, parser-miss, ontology-miss, contradiction-blocked). Each has a tested rescue path.

**Boot Determinism**: `seed-ontology!` on a fresh space always produces >= 10 atoms and >= 4 links.

## CI Coverage

### Job: lint-and-test
1. Shellcheck on all 4 scripts (coggy, coggy-client, coggyctl.sh, save-sessions.sh)
2. All 3 test suites (131 tests)
3. Boot smoke test (seed ontology, verify atom/link counts)
4. Web server smoke test (health, state, index, metrics, events, focus, observe, query, atom lookup)
5. Semantic pipeline smoke test (extract, ground, verify atoms)

### Job: smoke-and-artifacts
1. coggyctl.sh smoke (start, health check, stop)
2. API state capture (health.json, state.json)
3. Artifact upload for post-mortem

## API Contract

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | /health | 200 | Health check |
| GET | / | 200 | Chat UI |
| GET | /api/state | 200 | Full state snapshot |
| GET | /api/metrics | 200 | Semantic health metrics |
| GET | /api/events | 200 | Recent event log |
| GET | /api/focus | 200 | Attentional focus (top-N by STI) |
| GET | /api/atoms/:name | 200/404 | Single atom + TV + connected links |
| POST | /api/chat | 200 | Chat message (LLM roundtrip) |
| POST | /api/observe | 200 | Agent semantic observation (no LLM) |
| POST | /api/query | 200 | Query atoms + links + attention |
| POST | /api/stimulate | 200 | Nudge attention on named atoms |

## Not Yet Guaranteed

- **LLM integration**: No tests call OpenRouter. LLM path is smoke-tested structurally (pipeline runs) but not end-to-end.
- **Snapshot round-trip**: Save/restore works in practice but no automated test verifies full fidelity.
- **Web UI correctness**: HTML/JS served but not tested (no browser automation).
- **Concurrent access**: Atomspace uses atoms (Clojure atoms) which are thread-safe, but no concurrent stress tests exist.
- **Pattern matching**: Not yet implemented.
- **Backward chaining**: Not yet implemented.
