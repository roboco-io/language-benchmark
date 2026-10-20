#!/usr/bin/env bash
# Builds the application and runs it in the foreground. Port comes from $PORT (default 8080).
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-8080}"
gradle --no-daemon -q bootJar
exec java -jar build/libs/app.jar
