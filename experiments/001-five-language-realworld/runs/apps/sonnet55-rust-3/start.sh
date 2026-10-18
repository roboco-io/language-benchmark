#!/usr/bin/env bash
# Builds the server (cargo fetches dependencies) and runs it in the foreground.
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-8000}"
cargo build --release
exec ./target/release/realworld
