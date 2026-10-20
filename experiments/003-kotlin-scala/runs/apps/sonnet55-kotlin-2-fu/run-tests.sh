#!/usr/bin/env bash
# Runs the vendored official RealWorld Hurl tests against a running server.
set -euo pipefail
cd "$(dirname "$0")"
HOST="${HOST:-http://localhost:${PORT:-8080}}"
hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=${UID_VAL:-$(date +%s)$$}" hurl/*.hurl
