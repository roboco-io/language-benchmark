#!/usr/bin/env bash
# Runs the vendored RealWorld Hurl API tests against a running server.
# Usage: HOST=http://localhost:8080 ./run-api-tests.sh [file.hurl ...]
set -euo pipefail

cd "$(dirname "$0")"
HOST="${HOST:-http://localhost:${PORT:-8080}}" exec api/hurl/run-hurl-tests.sh "$@"
