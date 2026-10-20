#!/usr/bin/env bash
# Installs dependencies, builds the release distribution and runs the server in the foreground.
# The HTTP port is taken from $PORT (default 8000).
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"
export DATABASE_PATH="${DATABASE_PATH:-data/realworld.db}"

# Resolve dependencies and build the staged release distribution (target/universal/stage).
sbt -batch update stage

exec target/universal/stage/bin/realworld
