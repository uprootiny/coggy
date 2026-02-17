# Architectural Fit & Coherence

Coggy’s architecture is a living contract between deployment rigor, UX nuance, and knowledge articulation. This note ties together the decisions that make the system work, describes how to reproduce them, and gives an example trace of a working deployment.

## 1. Driving design decisions

1. **Stateful canvas + UX contracts** – The landing page (`static/index.html`) and the UX artifacts (`templates/ux-*.md`, `prompts/ux-perspective.md`) capture the feel, legibility, and living measurement promises that designers expect. They are built to highlight experimentation, actionables, and clickable deployments.
2. **Declarative runtime surface** – `src/bin/web.rs` shares the AtomSpace/Ecan loop with the CLI, exposes `/api/health`, `/api/feed`, `/api/focus`, `/api/trace`, and serves the same landing page so that dashboards, Claude, or Codex can rehydrate every expert judgment as JSON.
3. **High-port, multi-node deployment** – `scripts/run-web.sh` (per port) and `scripts/run-multi-web.sh` (batch) keep deployments on uncontested high numbers (8421/8431/8451) while logging to `logs/web-<port>.log`. `scripts/oom-tripwire.sh` guards against runaway RSS and is easy to schedule.
4. **Repository & CI integration** – `.github/workflows/ci.yml` runs `cargo fmt`, `clippy`, `test`, builds both binaries, and uploads artifacts so deployments always start from verified builds.
5. **Proxy & health visibility** – Caddy is configured to reverse-proxy `umbra.hyperstitious.art` (and other hosts) to `127.0.0.1:8421`, aligning with tmux status cues (`docs/deployment.md`), breadcrumbs, and monitoring rituals recorded under `breadcrumbs/`.

## 2. Working example: deploy + experiment loop

1. Launch a port mirror: `PORT_LIST="8421 8431 8451" ./scripts/run-multi-web.sh`. Each instance writes to `logs/web-<port>.log`.
2. Confirm the health API on a mirror: `curl http://localhost:8431/api/health`.
3. Run a trace: `curl 'http://localhost:8431/api/trace?input=cat%20is-a%20pet'` and copy the JSON into `breadcrumbs/` or `prompts/ux-perspective.md` to document the experiment (context, action, outcome, follow-up).
4. If memory climbs, run `./scripts/oom-tripwire.sh` or collect metrics via cron; threshold hits append `logs/oom-tripwire.log`.
5. Update Caddy (`/etc/caddy/Caddyfile`) and reload so `umbra.hyperstitious.art` routes to whichever port you want to serve and the tmux status line references the health endpoint for that port.

## 3. Coherence guardrails

- **Breadcrumbs** capture every action (`2026-02-16-coggy-deploy.md`, `2026-02-16-multi-deploy.md`, etc.) to keep context/action/outcome/follow-up visible.
- **Schemas/skills/prompts** link the expert judgments to downstream tasks (e.g., legal reasoning or adversarial experiments).
- **Monitoring** (Caddy health, OOM tripwire, tmux statuses) keeps the narrative truthful so other agents can trust `umbra.hyperstitious.art`.
- **Branching** (per your instruction) ensures any new layer of sophistication, example domain, or tooling addition starts on feature branches and is merged via small, frequent commits.

Use this document to check that new decisions (ontology overlap, new prompts, environment expansions) are consistent with the base architecture. If something feels out-of-sync, revisit the sections above before shipping.
