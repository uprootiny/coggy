# Coggy User Stories Backlog

Date: 2026-02-22  
Scope: AI-assisted + raw-human affordances with inspectable, reusable ontology workflows.

## UI Storyboard (Implemented 2026-03-01)

1. Ribbon gear opens settings drawer without disrupting chat flow.
2. API key section shows status dot (green/amber/red), source, masked key, and set/clear/show-hide controls.
3. Model picker in drawer ranks by live health and stays synchronized with the main input-bar model selector.
4. Temperature slider (`0.0..1.5`) live-saves via `POST /api/config`.
5. Max tokens slider (`256..4096`) live-saves via `POST /api/config`.
6. Drawer model-health table shows compact `ok/fail/cooldown/latency` telemetry.

## P0

1. As an ontology builder, I want suggestions for the next assertions so I can close grounding gaps quickly.
   - Endpoint: `POST /api/assist/suggest-next-assertions`
   - Done when: returns up to 5 candidate assertions with expected grounding gain.

2. As a first-time operator, I want a guided parse→ground→attend→infer→reflect walkthrough so I can verify the system end-to-end.
   - Endpoint: `GET /api/onboarding/walkthrough`
   - Done when: steps + success criteria are machine-checkable and human-readable.

3. As a practitioner, I want to save ad-hoc grounded ontologies and reload them later so knowledge is reusable across sessions.
   - Endpoints: `POST /api/ontology/save`, `GET /api/ontology/list`, `POST /api/ontology/load`
   - REPL: `/ont-save`, `/ont-list`, `/ont-load`
   - Done when: saved ontology includes concept/link counts and load reports deltas.

4. As a governance reviewer, I want a provenance-first export so decisions are auditable without hidden chain-of-thought.
   - Endpoint: `GET /api/governance/export`
   - Done when: output includes metrics, events, evidence, focus, and typed traces only.

## P1

1. As a query user, I want NL→query translation so I can inspect proof paths faster.
   - Endpoint: `POST /api/assist/nl-query`
   - Done when: response emits executable `/api/query` payload.

2. As a release owner, I want a go/conditional/no-go readiness signal so launch decisions are faster.
   - Endpoint: `GET /api/assist/release-readiness`
   - Done when: output includes score, verdict, and component metrics.

3. As a power user, I want forward/backward inference preview before running so I can estimate cost and risk.
   - Endpoint: `POST /api/infer/preview`
   - Done when: output includes predicted links, latency, and risk.

4. As a legal workflow maintainer, I want corpus ingestion status and controls so grounded legal contexts stay fresh.
   - Endpoints: `GET /api/ibid/status`, `POST /api/ibid/ingest`
   - Done when: run count, last path, and load counts are visible.

## P2

1. As an ontology maintainer, I want naming-collision detection so loader failures are caught early.
   - Future endpoint: `/api/assist/collision-check` (planned)

2. As a researcher, I want inferred links clustered by theme so emergent concept families are visible.
   - Future endpoint: `/api/assist/theme-clusters` (planned)

3. As an ops user, I want deterministic scenario suites for legal/forecast/bio so regressions stay reproducible.
   - Existing base: `GET /api/smoke`
   - Planned extension: domain-specific smoke profiles.
4. As a systems investigator, I want multi-page state assessment dumps so I can map interactions across layers.
   - Endpoints: `GET/POST /api/assess/unroll`
   - REPL: `/assess [tag]`

## Scenario Recipes

## Scenario A: Save a reusable grounded ontology

1. Run a few turns in your target domain (`POST /api/chat`).
2. Save ontology:
   ```bash
   curl -sS -X POST http://localhost:59683/api/ontology/save \
     -H 'content-type: application/json' \
     -d '{"id":"legal-issue-authority-v1","include_focus":true,"min_sti":2.0,"min_confidence":0.45}' | jq
   ```
3. List saved ontologies:
   ```bash
   curl -sS http://localhost:59683/api/ontology/list | jq
   ```
4. Reload later:
   ```bash
   curl -sS -X POST http://localhost:59683/api/ontology/load \
     -H 'content-type: application/json' \
     -d '{"id":"legal-issue-authority-v1"}' | jq
   ```

## Scenario B: AI-assisted query loop

1. Translate question into structured query:
   ```bash
   curl -sS -X POST http://localhost:59683/api/assist/nl-query \
     -H 'content-type: application/json' \
     -d '{"question":"which concepts are central to burden shift?"}' | jq
   ```
2. Execute emitted payload via `POST /api/query`.
3. Request next assertions:
   ```bash
   curl -sS -X POST http://localhost:59683/api/assist/suggest-next-assertions \
     -H 'content-type: application/json' \
     -d '{"concepts":["burden-shift","authority-weight"]}' | jq
   ```

## Scenario C: Governance checkpoint before release

1. Readiness:
   ```bash
   curl -sS http://localhost:59683/api/assist/release-readiness | jq
   ```
2. Provenance export:
   ```bash
   curl -sS http://localhost:59683/api/governance/export | jq
   ```

## Scenario D: Unroll assessment + mindmap artifacts

1. Trigger assessment bundle:
   ```bash
   curl -sS -X POST http://localhost:59683/api/assess/unroll \
     -H 'content-type: application/json' \
     -d '{"tag":"release-candidate"}' | jq
   ```
2. Inspect generated files under `docs/assessments/<run-id>/`.

## Scenario D: Capability measurements and scoring

1. Smoke capability score:
   ```bash
   curl -sS http://localhost:59683/api/smoke | jq
   ```
2. Readiness weighted score + verdict:
   ```bash
   curl -sS http://localhost:59683/api/assist/release-readiness | jq
   ```
3. Runtime model-health ranking:
   ```bash
   curl -sS http://localhost:59683/api/openrouter/models | jq
   ```
