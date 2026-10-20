#!/usr/bin/env bash
# Resolve dependencies, build the application, and run the server in the foreground.
# The server listens on $PORT (default 8080).
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8080}"

gradle --no-daemon -q bootJar

exec java --enable-native-access=ALL-UNNAMED -jar build/libs/realworld-1.0.0.jar
