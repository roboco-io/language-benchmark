#!/usr/bin/env bash
# Installs dependencies, builds the server, applies database migrations and
# starts the server in the foreground. The listening port comes from PORT.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"
mkdir -p data
export DATABASE_URL="${DATABASE_URL:-file:$(pwd)/data/realworld.db}"

npm ci --no-audit --no-fund
npm run build
npx prisma migrate deploy
exec node dist/index.js
