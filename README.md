# Coggy

Coggy is an inspectable ontology-first reasoning harness with:
- AtomSpace-style hypergraph memory (`atoms`, `links`, truth values)
- Layered reasoning traces (`PARSE`, `GROUND`, `ATTEND`, `INFER`, `REFLECT`)
- OpenRouter-backed LLM bridge with inspectable diagnostics
- A stateful retro UI with live attention/focus visualization

## Live UI (current session)

- Local: `http://localhost:59683`
- Public: `http://173.212.203.211:59683`

Health check:

```bash
curl -sS http://173.212.203.211:59683/health
```

## Quick Start

```bash
cd ~/coggy
nix-shell
./coggy start
./coggy status
```

Run on a novel high port:

```bash
COGGY_PORT=59683 HYLE_PORT=59684 ./coggy start
COGGY_PORT=59683 HYLE_PORT=59684 ./coggy status
```

## OpenRouter Diagnostics (Inspectable)

```bash
./coggy doctor --json
curl -sS http://localhost:59683/api/openrouter/status | jq
```

Smoke tests (self-contained, includes temporary server + OpenRouter auth probe):

```bash
./coggy smoke
```

## Worked Example 1: Legal Reasoning Study Stub

Goal: encode expert legal judgment as inspectable structures.

Prompt:

```text
Given partially occluded case facts, identify likely controlling authority,
what is missing, and confidence-calibrated next questions.
```

What to inspect:
- `GROUND` rate against existing legal concepts
- `INFER` deltas between turns
- failure badges (`parser miss`, `grounding vacuum`)

Useful API calls:

```bash
curl -sS http://localhost:59683/api/state | jq '.atoms | length'
curl -sS http://localhost:59683/api/metrics | jq
```

## Worked Example 2: Plasmid/Peptide Knowledge Study Stub

Goal: high-level provenance-first reasoning over conflicting evidence.

Prompt:

```text
Compare two peptide design rationales with conflicting assay narratives.
Show confidence and what additional evidence would reduce uncertainty.
```

Expected behavior:
- explicit uncertainty handling in trace
- conflict surfaced in `REFLECT`
- context-aware attention shifts in `ATTEND`

Important:
- Keep this high-level and non-operational.
- Do not use Coggy for wet-lab procedural generation.

## UI Affordances

- Trace depth toggles: `1/2/3`
- Ghost toggles: `g`
- Semantic emphasis: `s`
- Help overlay: `?`
- Attention froth canvas: draggable bubbles with spring return

## Deployment / Operations

Start/stop:

```bash
./coggy start
./coggy stop
./coggy restart
```

Fleet/status:

```bash
./coggy fleet
./coggy status
```

Logs:

```bash
./coggy logs
```

## Branch + Integration

Current integration branch:

- `integration/stateful-ux-highport`

Push pattern:

```bash
git checkout integration/stateful-ux-highport
git add -A
git commit -m "..."
git push
```

If conflicts arise:
1. Create a short-lived integration branch from latest `main`.
2. Merge feature branch into integration branch.
3. Resolve conflicts there.
4. Re-test (`./coggy smoke`, `bb test/coggy/atomspace_test.clj`).
5. Merge integration branch back.
