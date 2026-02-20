# Changelog

All notable changes to Coggy are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] — 2026-02-20

First versioned release. Coggy is a working ontology-first reasoning harness with a shared knowledge substrate accessible to other agents.

### Added
- **AtomSpace**: Hypergraph knowledge store with typed atoms, links, and truth values
- **TV Revision**: PLN confidence-weighted merge for independent observations
- **Link Deduplication**: Content-based identity via `link-key`, TV revision on duplicates
- **ECAN Attention**: STI/LTI per atom, stimulation, decay, focus set computation
- **Spread Activation**: STI flows through link graph to all connected atoms
- **Semantic Pipeline**: Extract/normalize/ground/commit/spread/rescue loop
- **Rescue Strategies**: 5 failure types diagnosed and repaired (grounding-vacuum, budget-exhausted, parser-miss, ontology-miss, contradiction-blocked)
- **Agent API**: POST /api/observe, POST /api/query, POST /api/stimulate, GET /api/focus, GET /api/atoms/:name
- **9 Domain Packs**: legal, ibid-legal, forecast, bio, unix, research, balance, study, accountability
- **coggy-client**: Shell script for sibling project integration
- **Web UI**: Retro chat interface with trace rendering, attention visualization
- **Boot Ritual**: Deterministic ontology seeding
- **Snapshot System**: Save/restore session state with versioned snapshots
- **OpenRouter LLM Client**: Model selection, scoring, token tracking
- **Trace Renderer**: PARSE/GROUND/ATTEND/INFER/REFLECT reasoning skeleton
- **Benchmarks**: Grounding rate, attention health, pipeline speed, challenge drills
- **CI Pipeline**: 2-job workflow — lint+test (shellcheck, 131 tests, boot/web/semantic smoke) and smoke+artifacts
- **Project Documentation**: CLAUDE.md (architecture), GUARANTEES.md (guarantee surface), CHANGELOG.md

### Test Coverage
- 131 tests, 485 assertions across 3 suites
- All pass in < 1s
