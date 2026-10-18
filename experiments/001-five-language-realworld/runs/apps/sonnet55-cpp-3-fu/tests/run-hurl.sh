#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl API tests against a running server.
# Usage: PORT=8080 tests/run-hurl.sh   (or HOST=http://localhost:8080)
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${HOST:-http://localhost:${PORT:-8080}}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" "$DIR"/hurl/*.hurl
