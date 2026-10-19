#!/usr/bin/env bash
# Installs dependencies, builds the application and runs it in the foreground.
# The HTTP port is read from $PORT (default 8080).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v mvn >/dev/null 2>&1; then
  echo "error: Maven (mvn) is required but was not found on PATH" >&2
  exit 1
fi

export PORT="${PORT:-8080}"

echo "Resolving dependencies and building..."
mvn -B -q -DskipTests package

echo "Starting server on port $PORT"
exec java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -jar target/app.jar
