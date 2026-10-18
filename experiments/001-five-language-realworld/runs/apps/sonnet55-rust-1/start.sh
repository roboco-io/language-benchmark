#!/usr/bin/env bash
# Fetches dependencies, builds a release binary, and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"

cargo fetch --locked 2>/dev/null || cargo fetch
cargo build --release
exec ./target/release/realworld
