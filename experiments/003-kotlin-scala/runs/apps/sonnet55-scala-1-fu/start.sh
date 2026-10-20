#!/usr/bin/env bash
# Installs dependencies, builds a release (staged) distribution and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"
if command -v nproc >/dev/null 2>&1; then CORES="$(nproc)"; else CORES="$(sysctl -n hw.ncpu)"; fi
export CORES

# Keep the database in the repo (not inside the staged build directory) unless overridden.
mkdir -p data
export DATABASE_URL="${DATABASE_URL:-jdbc:sqlite:$PWD/data/realworld.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=true&synchronous=NORMAL}"

sbt -batch stage

exec target/universal/stage/bin/realworld \
  -Dhttp.port="$PORT" -Dhttp.address=0.0.0.0 \
  -Dplay.server.pidfile.path=/dev/null \
  -Dconfig.resource=application.conf
