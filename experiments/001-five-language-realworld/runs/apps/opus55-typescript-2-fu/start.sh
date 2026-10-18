#!/usr/bin/env bash
# Installs dependencies, builds the project and starts the server in the foreground.
# The server listens on $PORT (default 8000).
set -euo pipefail
cd "$(dirname "$0")"

if [ -f package-lock.json ]; then
  npm ci --no-audit --no-fund
else
  npm install --no-audit --no-fund
fi
npm run build

export NODE_ENV="${NODE_ENV:-production}"
exec node dist/index.js
