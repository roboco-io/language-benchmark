#!/usr/bin/env bash
# Installs dependencies, builds the release jar and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-8080}"
gradle bootJar --no-daemon -q
exec java --enable-native-access=ALL-UNNAMED -jar build/libs/app.jar
