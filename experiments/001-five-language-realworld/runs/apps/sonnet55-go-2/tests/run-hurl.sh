#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against a running server.
# Usage: HOST=http://localhost:8000 tests/run-hurl.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${HOST:-http://localhost:8000}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
exec hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" "$DIR"/hurl/*.hurl
