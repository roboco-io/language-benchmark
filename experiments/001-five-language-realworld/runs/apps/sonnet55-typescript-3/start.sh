#!/usr/bin/env bash
# Installs dependencies, builds, and runs the server in the foreground. Port comes from $PORT.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-3000}"
export DATABASE_URL="${DATABASE_URL:-file:./dev.db}"

npm install --no-audit --no-fund
npm run build
exec node dist/index.js
