#!/usr/bin/env bash
# Runs the vendored RealWorld Hurl suite against a running server (default http://localhost:8080).
set -euo pipefail
cd "$(dirname "$0")"
HOST="${HOST:-http://localhost:${PORT:-8080}}" ./hurl/run-hurl-tests.sh "$@"
