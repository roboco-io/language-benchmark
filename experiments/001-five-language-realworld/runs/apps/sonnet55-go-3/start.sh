#!/usr/bin/env bash
# Install dependencies, build, and run the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-8000}"
go mod download
go build -trimpath -ldflags="-s -w" -o bin/realworld .
exec ./bin/realworld
