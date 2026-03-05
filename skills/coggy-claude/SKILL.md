---
name: coggy-claude
description: "Command Claude Code to operate Coggy’s canvas, run benchmarks or demos, and document traces/health in breadcrumbs when you need the knowledge base captured end-to-end."
---

# Claude + Coggy Runbook

Use this skill when Claude needs to *drive* the Coggy deployment—run demos or benchmarks, refresh deployments, collect traces, and commit logs so Claude’s reasoning matches the stateful canvas.

## Quick workflow
1. **Review the architecture**: skim `docs/architecture-fit.md` and `docs/benchmarks.md` so Claude knows the UX/monitoring contract.
2. **Run the runtime scripts**
   * `scripts/run-multi-web.sh` to start high-port instances (8421/8431/8451).
   * `scripts/demo-run.sh` to collect `/api/health`, `/api/trace`, `/api/focus` outputs for a given prompt.
   * `scripts/run-benchmarks.sh` to exercise the curated scenarios and capture JSON traces/logs.
   * `scripts/oom-tripwire.sh` to log RSS usage and detect OOM risk.
3. **Surface outputs**
   * Append the resulting `logs/demo-<port>.log`, `logs/benchmarks-*.log`, and `logs/oom-tripwire.log` entries to a breadcrumb (context/action/outcome/follow-up).
   * If Claude updates `static/index.html` or scripts, rerun the demo/benchmark and reroute Caddy if necessary (`/etc/caddy/Caddyfile`).
4. **Reference UX docs**: use `docs/demo.md`, `docs/benchmarks.md`, and `docs/deployment.md` in prompts when describing capabilities to downstream agents.
5. **Health-check and commit**: every demo/benchmark run should finish with `curl http://localhost:<port>/api/health` plus screenshot/log capture before committing/pushing (see README sections for commit instructions).

## When to use this skill
- Claude needs to show the latest Coggy UI/trace to another agent or human.
- Claude wants to capture the current knowledge state (focus trace, trace log) before making architectural changes.
- Claude must redeploy the canvas (multi-port) under new high ports and inform watchers via breadcrumbs.

## Notes
- Outputs recorded in `logs/demo-*.log` and `logs/benchmarks-*.log` should be zipped or excerpted into `docs/example-trace.md` when used as reference evidence.
- Keep the runbook playtest results updated (`breadcrumbs/2026-02-17-runbook-playtest.md` etc.) whenever you refresh the flows.
