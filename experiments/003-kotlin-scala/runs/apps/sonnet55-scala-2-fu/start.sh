#!/usr/bin/env bash
# Installs dependencies, builds a release (staged) distribution and runs the server in the foreground.
# The listening port is read from the PORT environment variable (default 8000).
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"

sbt -batch stage

# exec keeps the server in the foreground and forwards signals to it.
exec ./target/universal/stage/bin/realworld-play \
  -Dhttp.port="$PORT" \
  -Dplay.server.pidfile.path=/dev/null \
  -J-XX:+UseParallelGC
