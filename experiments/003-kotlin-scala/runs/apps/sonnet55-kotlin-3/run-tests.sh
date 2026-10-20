#!/usr/bin/env bash
# Runs the vendored RealWorld Hurl tests against a running server (HOST, default http://localhost:8080).
set -euo pipefail
cd "$(dirname "$0")"
HOST="${HOST:-http://localhost:${PORT:-8080}}"
exec hurl --test --jobs 1 --variable "host=$HOST" --variable "uid=$(date +%s)$$" api/hurl/*.hurl
