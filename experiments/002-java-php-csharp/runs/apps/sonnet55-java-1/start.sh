#!/usr/bin/env bash
# Resolves dependencies, builds the release jar, and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8080}"

mvn -B -q -DskipTests package
exec java --enable-native-access=ALL-UNNAMED -jar target/realworld.jar
