#!/usr/bin/env bash
# Installs dependencies, builds the app, and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-8080}"
gradle bootJar --no-daemon -q
exec java -jar build/libs/app.jar
