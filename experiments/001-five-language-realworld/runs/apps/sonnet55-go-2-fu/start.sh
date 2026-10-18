#!/usr/bin/env bash
# Installs dependencies, builds an optimized binary, and runs the server in the foreground.
# The listen port comes from the PORT environment variable (default 8000).
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"

go mod download
mkdir -p bin
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o bin/realworld .

exec ./bin/realworld
