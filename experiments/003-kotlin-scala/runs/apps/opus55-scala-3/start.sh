#!/usr/bin/env bash
# Installs dependencies, builds a staged release and runs the server in the foreground.
# The server listens on $PORT (default 8000).
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v sbt >/dev/null 2>&1; then
  echo "sbt is required but was not found on PATH" >&2
  exit 1
fi

# Fetch dependencies and build the production distribution (target/universal/stage).
sbt -batch clean stage

PORT="${PORT:-8000}"
export DATABASE_PATH="${DATABASE_PATH:-$PWD/data/realworld.db}"
mkdir -p "$(dirname "$DATABASE_PATH")"

exec target/universal/stage/bin/realworld-play \
  -Dhttp.port="$PORT" \
  -Dpidfile.path=/dev/null
