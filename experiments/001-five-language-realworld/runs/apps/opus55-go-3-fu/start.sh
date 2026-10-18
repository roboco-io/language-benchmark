#!/usr/bin/env bash
# Install dependencies, build a release binary and run the server in the foreground.
# The server listens on $PORT (default 8000).
set -euo pipefail

cd "$(dirname "$0")"

go mod download
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o bin/server .

exec ./bin/server
