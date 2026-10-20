#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl API tests against a running server.
# Usage: HOST=http://localhost:8000 api/run-hurl.sh [file.hurl ...]
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${HOST:-http://localhost:8000}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
FILES=("$@")
[ ${#FILES[@]} -eq 0 ] && FILES=("$DIR"/hurl/*.hurl)
hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" "${FILES[@]}"
