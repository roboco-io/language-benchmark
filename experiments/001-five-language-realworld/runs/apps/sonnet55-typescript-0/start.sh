#!/usr/bin/env bash
# Installs dependencies, builds, and runs the server in the foreground.
# The port comes from $PORT (default 3000).
set -euo pipefail
cd "$(dirname "$0")"

if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
npm run build
export NODE_ENV=production
exec node dist/server.js
