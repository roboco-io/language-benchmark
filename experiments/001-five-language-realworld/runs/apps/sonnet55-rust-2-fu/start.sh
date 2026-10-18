#!/usr/bin/env bash
# Builds the server in release mode and runs it in the foreground.
# Reads the listening port from $PORT (default 8000).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v cargo >/dev/null 2>&1; then
  echo "cargo not found; install the Rust toolchain first" >&2
  exit 1
fi

export PORT="${PORT:-8000}"
cargo build --release --locked 2>/dev/null || cargo build --release
exec ./target/release/realworld
