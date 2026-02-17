# Contemporaneous formal benchmarks & challenges

Coggy’s architecture spans knowledge-grounding loops, UX commitments, and resilient deployment. The benchmarks below combine symbolic reasoning tasks with deployment/monitoring challenges so we can regularly surface where the system holds up.

## Symbolic reasoning & knowledge benchmarks

1. **Legal/adversarial judgments** – Encode partially occluded case facts via `/api/trace` and compare the inference chain with expected legal conclusions (e.g., inheritance of statutes). Track each run in breadcrumbs/prompts (`docs/example-trace.md`, `prompts/ux-perspective.md`).
2. **Math & formal verification (MATH/GSM8K style)** – Feed arithmetic or proof-language inputs through `/api/trace?input=...` and ensure PLN deduces the correct chain (evidence recorded in `logs/demo-*.log` before/after modifications).
3. **Domain ontology coverage** – Validate that new concepts added to `ontology.rs` appear in `/api/feed` and `/api/focus` (setup via `scripts/run-benchmarks.sh` scenario inputs).
4. **Experiment contract completeness** – Each benchmark run should produce breadcrumbs documenting context/action/outcome/follow-up so we can prove the UX contract (see `breadcrumbs/` entries).

## Deployment & observability challenges

1. **Multi-port resilience** – Run `scripts/run-multi-web.sh` to launch 8421/8431/8451 and keep `docs/deployment.md` instructions in sync; ensure Caddy reverse proxy (per `/etc/caddy/Caddyfile`) can pivot ports when needed.
2. **OOM tripwire compliance** – Schedule `scripts/oom-tripwire.sh` via cron or tmux/pane so it logs RSS usage and warns before restarts (`logs/oom-tripwire.log` is the artifact).
3. **Smoke health & focus tests** – Use `curl http://localhost:<port>/api/health` and `/api/focus` during deployments (documented in `docs/demo.md` and commit messages) to verify endpoints remain stable.

## Maintaining visibility

- Run `PORT=8451 ./scripts/run-benchmarks.sh` after any change to the AtomSpace, parser, or UX assets; check `logs/benchmarks-*.log` for scenario outputs.
- Push each benchmark result as a breadcrumb or comment to capture the exact smoke state (`breadcrumbs/2026-02-17-multi-benchmarks.md` can be created for future runs).
- Ensure the README/AGENTS references this doc so future agents can instantly locate the current benchmark suite.
