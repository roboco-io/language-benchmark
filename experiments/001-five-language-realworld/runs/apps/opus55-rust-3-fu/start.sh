#!/usr/bin/env bash
# Fetches dependencies, builds an optimized binary and runs the server in the
# foreground. The listening port comes from $PORT (default 8080).
set -euo pipefail

cd "$(dirname "$0")"

export PORT="${PORT:-8080}"
export DATABASE_URL="${DATABASE_URL:-sqlite:data/realworld.db}"

cargo fetch --locked
cargo build --release --locked

exec ./target/release/realworld
