#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against a running server (HOST, default localhost:$PORT).
set -euo pipefail
cd "$(dirname "$0")"
HOST="${HOST:-http://localhost:${PORT:-8080}}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" api/hurl/*.hurl
