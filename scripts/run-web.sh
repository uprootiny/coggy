#!/usr/bin/env bash
set -euo pipefail

PORT="${COGGY_PORT:-8421}"

echo "Building Coggy web server (port ${PORT})..."
cargo build --release --bin web

echo "Starting Coggy web server on port ${PORT}..."
COGGY_PORT="${PORT}" cargo run --release --bin web
