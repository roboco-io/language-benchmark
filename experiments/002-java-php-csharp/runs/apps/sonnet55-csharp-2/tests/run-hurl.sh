#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against HOST (default http://localhost:8080).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${HOST:-http://localhost:${PORT:-8080}}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" "$DIR"/hurl/*.hurl
