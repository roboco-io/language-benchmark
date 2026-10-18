#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against a running server.
# Usage: HOST=http://localhost:8000 tests/run-hurl.sh [file.hurl ...]
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${HOST:-http://localhost:${PORT:-8000}}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"

FILES=("$@")
if [ ${#FILES[@]} -eq 0 ]; then
  FILES=("$DIR"/hurl/*.hurl)
fi

hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" "${FILES[@]}"
