# Coggy-style AI three layers: benchmarks, diagnostics, challenge drills

Use this reference when you want to plan experiments, spot regressions, or train Claude + Codex on how to keep Coggy reliable. Each layer is expected to output measurable signals (passes/failures, logs, breadcrumbs) so we can drive the project forward with data.

## Benchmarks (pass/fail + metrics)

1. **Promptset trace benchmark**  
   - Inputs: canonical legal/math/formal/smoke/commit prompts defined in `experiments/promptsets.json`.
   - Metrics: pass rate (did trace produce `Reflect`/`Infer` sections), latency, trace completeness (are all phases present), focus coverage (expected atoms surfaced).
2. **Smoke-loop benchmark**  
   - Run: `scripts/demo-run.sh`, observe blocker counts (errors, model stops).  
   - Score: blocker-count reduction per rerun (first blocker -> fix -> rerun).  
   - Record breadcrumb with “blocker → fix → outcome”.
3. **Free-model reliability benchmark**  
   - Hit `/api/trace` via OpenRouter-compatible `/models/gpt-4o-mini` vs `/models/gpt-neox` etc.  
   - Metrics: success rate (non-hallucinated trace), latency, observed focus atoms.  
   - Log results in `logs/experiments.csv`.
4. **Recovery benchmark**  
   - Inject blocker (auth gate, parser miss, approval gate) via replay script or manual misconfigured inputs.  
   - Measure time-to-recovery (minutes) and log the steps taken to re-establish invariants.
5. **Throughput benchmark**  
   - Track “actions per minute” that cause verified improvements (new atoms, new trace traces).  
   - Use trace/inference counts to prove real progress rather than just commands issued.

## Diagnostics (continuous observability)

1. **Liveness & ownership** – map running tmux panes/sessions with Coggy instances (`logs/web-*.log`, `tmux list-panes`). Tag idle panes and reassign.
2. **Blocker classifier** – label outcomes as auth failure, parser miss, tool failure, loop drift, or other; use these tags to prioritize Recovery drills.
3. **Haywire detector** – monitor logs for repeated unprompted actions (auto-proceed loops, repeated “status” commands) and flag them in breadcrumbs.
4. **Trace quality metrics** – compute grounding rate (how often `Ground` finds matches), parse success, confidence delta vs. trust, and `Reflect` delta (new atoms/inferences).  
5. **Evidence logging** – every intervention should append Observation → Decision → Action → Outcome so downstream agents can audit changes.

## Challenge Drills (stress scenarios)

1. **“No user response” discipline** – pause automatic command feeds until you have a checksum or human ack; prevents runaways.
2. **“First blocker only”** – when a blocker appears, stop everything else and keep focus on that blocker until the smoke suite is green.
3. **Model outage drill** – fail one free OpenRouter model and route to another while keeping trace/results consistent.
4. **Contradictory-context drill** – detect conflicting goals in branched sessions, warn the operator, then reconcile before continuing.
5. **Stale-loop drill** – detect repeated zero-effort cycles and trigger the recovery playbook defined in `docs/deployment.md`.

Use these layers together: benchmarks prove the system works, diagnostics surface drifting signals, and drills rehearse recovery behaviors. Keep results logged (`logs/benchmarks-*.log`, `logs/experiments.csv`, breadcrumbs) and feed them into Claude’s `coggy-claude` skill for the next iteration.
