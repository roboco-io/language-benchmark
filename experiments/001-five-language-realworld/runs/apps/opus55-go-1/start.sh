#!/usr/bin/env bash
# Install dependencies, build a release binary and run the server in the
# foreground. The listening port is taken from $PORT (default 8080).
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8080}"
export CGO_ENABLED=0

echo "==> Downloading Go modules"
go mod download

echo "==> Building release binary"
go build -trimpath -ldflags="-s -w" -o bin/realworld .

echo "==> Starting server on port $PORT"
exec ./bin/realworld
