#!/usr/bin/env bash
# Installs dependencies, builds, and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-3000}"
export NODE_ENV="${NODE_ENV:-production}"
export DATABASE_URL="${DATABASE_URL:-file:$PWD/data/app.db}"
npm install --include=dev --no-audit --no-fund
npm run build
exec node dist/server.js
