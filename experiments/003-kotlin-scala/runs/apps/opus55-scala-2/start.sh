#!/usr/bin/env bash
# Resolves dependencies, builds a production (staged) release and runs the server in the foreground.
# The HTTP port is taken from the PORT environment variable (default 8080).
set -euo pipefail

cd "$(dirname "$0")"
export PORT="${PORT:-8080}"
# Keep the database outside target/ so it survives rebuilds.
export DATABASE_PATH="${DATABASE_PATH:-$PWD/data/realworld.db}"

sbt -batch clean stage

exec target/universal/stage/bin/realworld-play
