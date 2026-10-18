#!/usr/bin/env bash
# Install dependencies, build, and run the RealWorld API server in the foreground.
# The server listens on $PORT (default 3000).
set -euo pipefail

cd "$(dirname "$0")"

npm ci --no-audit --no-fund
npm run build

exec node dist/index.js
