#!/usr/bin/env bash
# Install dependencies, build, and run the RealWorld API server in the foreground.
# The server listens on $PORT (default 8000).
set -euo pipefail
cd "$(dirname "$0")"

export CGO_ENABLED=0
go mod download
go build -trimpath -ldflags="-s -w" -o bin/realworld .

exec ./bin/realworld
