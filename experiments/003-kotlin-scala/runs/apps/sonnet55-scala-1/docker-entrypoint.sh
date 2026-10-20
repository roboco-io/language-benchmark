#!/bin/sh
# Container entrypoint: size the thread pools to the CPU count and run the release build.
set -e
export PORT="${PORT:-8000}"
export CORES="${CORES:-$(nproc)}"
mkdir -p /data
exec /app/bin/realworld -Dhttp.port="$PORT" -Dhttp.address=0.0.0.0 -Dplay.server.pidfile.path=/dev/null
