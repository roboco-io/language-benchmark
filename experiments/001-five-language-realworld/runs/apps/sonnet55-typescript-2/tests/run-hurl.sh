#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against $HOST.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)/hurl"
HOST="${HOST:-http://localhost:${PORT:-8000}}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
exec hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" "$DIR"/*.hurl
