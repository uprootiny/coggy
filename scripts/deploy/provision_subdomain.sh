#!/usr/bin/env bash
set -euo pipefail

# Provision or preview nginx/certbot setup for a project subdomain.
# Safe by default (DRY_RUN=1).
#
# Usage:
#   ./scripts/deploy/provision_subdomain.sh <subdomain> [port]
#
# Env:
#   DOMAIN=hyperstitious.org
#   NGINX_CONF=/etc/nginx/sites-available/hyperstitious.conf
#   DRY_RUN=1

SUBDOMAIN="${1:-}"
PORT="${2:-}"
DOMAIN="${DOMAIN:-hyperstitious.org}"
NGINX_CONF="${NGINX_CONF:-$HOME/agentiess/nginx-agentiess.conf}"
DRY_RUN="${DRY_RUN:-1}"

if [[ -z "$SUBDOMAIN" ]]; then
  echo "usage: $0 <subdomain> [port]" >&2
  exit 1
fi

FQDN="${SUBDOMAIN}.${DOMAIN}"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

if [[ -n "$PORT" ]]; then
  cat >"$tmp" <<NGINX
# ${SUBDOMAIN} - coggy project (proxy ${PORT})
server {
  listen 443 ssl http2;
  listen [::]:443 ssl http2;
  server_name ${FQDN};

  ssl_certificate /etc/letsencrypt/live/${FQDN}/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/${FQDN}/privkey.pem;

  location / {
    proxy_pass http://127.0.0.1:${PORT};
    proxy_http_version 1.1;
    proxy_set_header Upgrade \$http_upgrade;
    proxy_set_header Connection 'upgrade';
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
  }
}
NGINX
else
  cat >"$tmp" <<NGINX
# ${SUBDOMAIN} - coggy project (static placeholder)
server {
  listen 443 ssl http2;
  listen [::]:443 ssl http2;
  server_name ${FQDN};

  ssl_certificate /etc/letsencrypt/live/${FQDN}/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/${FQDN}/privkey.pem;

  root /var/www/drops/${SUBDOMAIN};
  index index.html;
  location / {
    try_files \$uri \$uri/ /index.html;
  }
}
NGINX
fi

echo "=== subdomain provisioning plan ==="
echo "fqdn: ${FQDN}"
echo "nginx_conf: ${NGINX_CONF}"
echo "dry_run: ${DRY_RUN}"
echo
cat "$tmp"
echo

if [[ "$DRY_RUN" == "1" ]]; then
  echo "dry run only; no system changes applied"
  exit 0
fi

echo "requesting cert via certbot..."
sudo certbot --nginx -d "$FQDN" --non-interactive --agree-tos --redirect
echo "appending nginx block..."
cat "$tmp" | sudo tee -a "$NGINX_CONF" >/dev/null
echo "testing and reloading nginx..."
sudo nginx -t
sudo nginx -s reload
echo "done: https://${FQDN}"

