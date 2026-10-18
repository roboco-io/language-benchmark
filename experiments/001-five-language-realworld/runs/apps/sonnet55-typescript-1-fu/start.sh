#!/usr/bin/env bash
# Installs dependencies, builds, and starts the server in the foreground.
# Port comes from $PORT (default 3000). The database lives in ./data/app.db unless DATABASE_URL is set.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-3000}"
export DATABASE_URL="${DATABASE_URL:-file:$PWD/data/app.db}"

# Dev dependencies (typescript, prisma) are required for the build.
npm install --include=dev --no-audit --no-fund
# `prisma generate` reads DATABASE_URL from the environment; the build is a plain tsc compile.
npm run build

NODE_ENV=production exec node dist/server.js
