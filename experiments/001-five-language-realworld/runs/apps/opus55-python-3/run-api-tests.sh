#!/usr/bin/env bash
# Run the vendored RealWorld Hurl API tests against a running server.
# Usage: ./run-api-tests.sh [host]   (default host: http://localhost:${PORT:-8000})
set -euo pipefail
cd "$(dirname "$0")"
HOST="${1:-${HOST:-http://localhost:${PORT:-8000}}}" exec api/hurl/run-hurl-tests.sh
