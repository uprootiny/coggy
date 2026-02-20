# Coggy Design Document

## Origins

Coggy began as a question: what if an LLM's reasoning trace were not prose decoration but auditable ontological structure? The answer became a hypergraph knowledge store (AtomSpace), an economic attention mechanism (ECAN), and a semantic pipeline that enforces machine-checkable contracts on every LLM response.

The name reflects the project's stance: coggy is a phantasm — a reasoning-about-reasoning engine that reconstructs itself ab nihilo each session. The substrate (hyle) receives form (morphe) through the act of naming.

## Design Principles

These come from the global CLAUDE.md preference gradients, instantiated for coggy:

1. **Ontology over accident** — atoms and links are typed, named, truth-valued. Not free-text.
2. **Semantics over procedure** — the system represents *what things mean*, not just what steps were taken.
3. **Structure over state drift** — the atomspace is an inspectable snapshot. State transitions are events.
4. **Proof over assumption** — 131 tests, 485 assertions. Invariants are documented in GUARANTEES.md.
5. **Composition over entanglement** — 12 modules with declared interfaces. No module reaches into another's internals.
6. **Data over control** — domain packs are data structures, not code. Rescue strategies are dispatched by type, not nested if/else.
7. **Observability over mystery** — every reasoning step is visible in the trace (PARSE/GROUND/ATTEND/INFER/REFLECT).

## Architecture As-Is (v0.1.0)

### Core Loop

```
human/agent → input
                ↓
          [semantic.clj]
          extract → normalize → ground → commit → spread → rescue
                ↓                          ↓         ↓
          [atomspace.clj]            [attention.clj]  ↓
          atoms, links, TVs          STI/LTI, focus   ↓
                ↓                          ↓         ↓
          [trace.clj]                      ↓         ↓
          visible reasoning skeleton ←─────┘         ↓
                ↓                                    ↓
          [llm.clj]                            [rescue path]
          LLM transformation ←─ rescue may re-prompt
                ↓
          response + trace
```

### Data Model

**Atoms**: Named entities with typed truth values `(stv strength confidence)`.
- `ConceptNode` — things, ideas, entities
- `PredicateNode` — relations, properties, verbs

**Links**: Typed connections between atoms, also truth-valued.
- `InheritanceLink` — X is-a Y (taxonomy)
- `ImplicationLink` — if X then Y (causation)
- `SimilarityLink` — X resembles Y (analogy)
- `EvaluationLink` — predicate applied to arguments
- `ContextLink` — scoping

**Truth Values**: `{:tv/strength s :tv/confidence c}` where both in [0,1]. Revision merges independent observations via PLN formula: strength is confidence-weighted average, confidence grows.

**Attention**: Each atom has STI (Short-Term Importance). Stimulation adds STI. Decay subtracts. Spread activation distributes STI through links. Focus set = top-N by STI.

### Agent API

External agents interact without LLM roundtrip:
- `POST /api/observe` — push semantic observations (concepts + relations + confidence)
- `POST /api/query` — ask what coggy knows about named concepts
- `POST /api/stimulate` — boost attention on atoms
- `GET /api/focus` — read current attentional focus
- `GET /api/atoms/:name` — look up single atom with links

### Failure Types

The semantic pipeline diagnoses 5 failure types, each with a rescue strategy:
1. **grounding-vacuum** — nothing latches to existing atoms → re-seed with broader concepts
2. **parser-miss** — semantic block absent/malformed → generate fallback semantic
3. **ontology-miss** — concepts exist but no relations ground → extend relations via shared parents
4. **budget-exhausted** — ECAN funds depleted → partial commit of highest-confidence items
5. **contradiction-blocked** — conflicting truth values → TV revision to reconcile

## Process History

The build progressed in phases, each adding a layer of guarantee:

**Phase 0** (commits 1-8): Skeleton. AtomSpace, ECAN, trace renderer, LLM client, boot ritual, REPL, web UI. No tests.

**Phase 1** (commits 9-20): Contracts. Semantic pipeline with typed failure modes. First test suite. CI/CD. Result types for totality. Property tests for algebraic invariants.

**Phase 2** (commits 21-35): Depth. TV revision, spread activation, rescue implementations, agent-facing API, link deduplication, domain packs. Test count: 43 → 131.

**Phase 3** (commits 36-40): Governance. CLAUDE.md, GUARANTEES.md, CHANGELOG.md. Tag v0.1.0. This document.

Each phase made the guarantee surface larger and the system more inspectable. The direction is: **explicit → typed → composable → inspectable → reproducible → provable**.

## Design for v0.2.0

### Theme: Inference and Composition

v0.1.0 builds the substrate. v0.2.0 makes it *think*.

### 1. Pattern Matching

The atomspace currently supports only direct lookup (`get-atom`, `query-links`). v0.2.0 adds pattern matching: query with variables that unify against the hypergraph.

```clojure
;; Find all X where X inherits-from "authority"
(match space {:type :InheritanceLink :source '?x :target "authority"})
;; => [{?x "precedent"} {?x "statute"} {?x "regulation"}]
```

This is the prerequisite for inference rules. Without pattern matching, rules can't fire.

**Module**: `src/coggy/pattern.clj` (new)
**Tests**: Pattern variable binding, multi-variable, nested patterns, no-match

### 2. Forward Chaining (Rule Engine)

Rules fire when their premises match the atomspace. Each rule has a premise pattern, a conclusion template, and a TV formula.

```clojure
{:name "deduction"
 :premises [{:type :InheritanceLink :source '?a :target '?b}
            {:type :InheritanceLink :source '?b :target '?c}]
 :conclusion {:type :InheritanceLink :source '?a :target '?c}
 :tv-formula :deduction}  ;; PLN deduction formula
```

The forward chainer runs after each commit: match all rules, fire those with novel bindings, add conclusions to atomspace. This is how coggy draws inferences from accumulated knowledge.

**Module**: `src/coggy/inference.clj` (new)
**Tests**: Deduction chain, modus ponens, abduction, analogy, no infinite loop

### 3. Domain Composition

v0.1.0 seeds one domain at a time. v0.2.0 allows multiple domains active simultaneously, with cross-domain inference.

```clojure
(domain/seed-domain! space bank "legal")
(domain/seed-domain! space bank "accountability")
;; Now inference can connect legal-case → commitment → discrepancy
```

**Changes**: `domain.clj` — track active domains, avoid re-seeding, merge attention budgets.

### 4. WebSocket Event Stream

v0.1.0's agent API is request/response. v0.2.0 adds a WebSocket endpoint that streams events as they happen: atom additions, link creations, attention shifts, inference firings.

```
ws://localhost:PORT/ws/events
→ {"type":"atom-added","name":"precedent","tv":{"s":0.8,"c":0.6}}
→ {"type":"inference","rule":"deduction","bindings":{"a":"statute","b":"authority","c":"legal-norm"}}
→ {"type":"attention","atom":"precedent","sti":15.2,"delta":3.0}
```

This enables real-time visualization and live agent coordination.

**Changes**: `web.clj` — WebSocket upgrade handler, event broadcast

### 5. Squint Web UI

Replace the static HTML chat UI with a reactive Squint (ClojureScript-to-JS) application:
- Force-directed graph visualization of the atomspace
- Inference trace timeline (scrub through reasoning steps)
- Attention heatmap (which atoms are hot, which are fading)
- Domain selector with live seeding
- Agent activity feed (who observed what, when)

**Module**: `resources/public/` — Squint source compiled to JS
**Dependencies**: Squint compiler (runs in bb)

### 6. Snapshot Fidelity Test

Add automated round-trip test: seed → snapshot → clear → restore → verify identical state. Close the known gap in GUARANTEES.md.

### 7. Concurrent Stress Test

Add multi-threaded test: N agents observing simultaneously, verify atomspace consistency (no lost atoms, no duplicate links, TVs converge).

## Release Cadence

- **v0.1.x** — bug fixes, test additions, documentation
- **v0.2.0** — pattern matching + forward chaining + domain composition
- **v0.3.0** — WebSocket events + Squint UI + visualization
- **v0.4.0** — backward chaining + goal-directed inference

Each point release expands the guarantee surface documented in GUARANTEES.md.

## Verification Strategy

Every new capability gets:
1. Unit tests (in the relevant `*_test.clj` suite)
2. Property tests where algebraic laws apply
3. CI smoke test
4. GUARANTEES.md update
5. CHANGELOG.md entry

The direction is always: **make the guarantee surface larger with each commit**.
