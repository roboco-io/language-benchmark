#!/usr/bin/env bash
# Fetches dependencies, builds an optimized binary, and runs the server in the foreground.
# Environment: PORT (default 8000), DATABASE_URL (default sqlite://realworld.db), JWT_SECRET.
set -euo pipefail
cd "$(dirname "$0")"

cargo fetch
cargo build --release --locked

export PORT="${PORT:-8000}"
exec ./target/release/realworld-axum
