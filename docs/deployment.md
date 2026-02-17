# Deployment, Alternates, and Observability

The Coggy web experience is designed to run alongside the CLI while emitting knowledge-based signals to dashboards and experiments.

## Running the web server

```bash
COGGY_PORT=8421 cargo run --bin web
```

The landing page and API will be live at `http://localhost:8421` (or `http://173.212.203.211:8421` on the remote host). Use the `scripts/run-web.sh` wrapper to stay declarative:

```bash
./scripts/run-web.sh
```

The script reads `COGGY_PORT` and builds the binary in release mode before launching it.

## tmux + clickable deployments

Store the following snippet in your tmux status line or dashboard so the actionable URL is always visible:

```
set -g status-right "Coggy: #[fg=cyan]http://localhost:8421/api/health"
```

On remote servers, replace `localhost` with the public IP (e.g., `173.212.203.211`). You can also spin up alternates (e.g., for staging) by setting `COGGY_PORT=8431` and launching another `web` instance in a separate tmux pane.

## GitHub CI/CD & artifacts

The `.github/workflows/ci.yml` pipeline:

1. Checks formatting and lints with Clippy.
2. Runs the unit tests that cover AtomSpace, PLN, ECAN, parser, and diagnostics.
3. Builds release binaries for both the CLI (`coggy`) and the web server (`web`).
4. Uploads the landing page and binaries as artifacts named `coggy-releases`.

You can wire a deployment job to download the artifact and copy `static/index.html` + `target/release/web` onto the server, then supervise it with `systemd` or tmux.

## Data feeds and alternatives

Bravo dashboards, Claude agents, and external services only need to poll `/api/feed` and `/api/trace` to stay synchronized with the knowledge base. Documented experiments and breadcrumbs can reference the trace outputs, turning them into reproducible contracts.

### Alternate deployments

Set `COGGY_PORT` before invoking the script to create isolated experiments. For example:

```bash
COGGY_PORT=8431 ./scripts/run-web.sh  # alternate mirror
```

Use DNS (e.g., `umbra.hyperstitious.art`) or load balancers to route the public URL to the desired port. The API remains stable across ports, enabling multiple canvases per host.

## Production routing wrap

On the live host the Coggy web binary now listens on port `8421` and Caddy forwards `umbra.hyperstitious.art` to `127.0.0.1:8421`. To keep this routing tidy:

1. Run `COGGY_PORT=8421 ./scripts/run-web.sh` under tmux or a systemd service and capture stdout/stderr under `coggy/logs/web.log`.
2. Ensure `/etc/caddy/Caddyfile` includes the `reverse_proxy 127.0.0.1:8421` stanza for `umbra.hyperstitious.art` and reload Caddy via `sudo systemctl reload caddy`.
3. Verify the deployment with `curl -I https://umbra.hyperstitious.art` or the health API `/api/health`.
4. Tie tmux status lines to `http://localhost:8421/api/health` (or the public URL) so clickable badges reflect what is live.

This keeps the canvases up-to-date, signals stateful health, and feeds downstream agents through the API surface documented earlier.

## Multi-port deployments & OOM tripwires

Use `scripts/run-multi-web.sh` to launch Coggy web on several uncontested high ports at once (default ports: 8421, 8431, 8451). It watches for existing listeners, writes logs to `logs/web-<port>.log`, and can be invoked with `PORT_LIST="8421 8431 8451 8461" ./scripts/run-multi-web.sh`.

Keep memory in check by running `scripts/oom-tripwire.sh` regularly (scheduled via `cron`, `systemd-timer`, or manually) to log RSS usage for every `target/release/web` process and flag anything over `THRESHOLD_MB` (default 250 MB). The log `logs/oom-tripwire.log` records each probe so you can correlate spikes with deployment events.
