#!/usr/bin/env bash
# Run the vendored RealWorld Hurl suite against a running server.
# Usage: scripts/run-hurl.sh [HOST]   (default http://localhost:${PORT:-8080})
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${1:-${HOST:-http://localhost:${PORT:-8080}}}" exec "$ROOT/api/hurl/run-hurl-tests.sh"
