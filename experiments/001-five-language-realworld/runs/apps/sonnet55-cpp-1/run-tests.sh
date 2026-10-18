#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against a running server.
# Usage: HOST=http://localhost:8080 ./run-tests.sh [file.hurl ...]
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${HOST:-http://localhost:8080}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
FILES=("$@")
[ ${#FILES[@]} -gt 0 ] || FILES=("$DIR"/tests/hurl/*.hurl)
hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" "${FILES[@]}"
