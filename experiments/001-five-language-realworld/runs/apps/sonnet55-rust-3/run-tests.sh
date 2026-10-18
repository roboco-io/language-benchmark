#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against HOST (default http://localhost:8000).
set -euo pipefail
cd "$(dirname "$0")"
HOST="${HOST:-http://localhost:8000}"
UID_VAL="${UID_VAL:-$(date +%s)$$}"
hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$UID_VAL" api/hurl/*.hurl
