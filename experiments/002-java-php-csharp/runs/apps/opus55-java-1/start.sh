#!/usr/bin/env bash
# Installs dependencies, builds the application and runs it in the foreground.
# The HTTP port is read from $PORT (default 8080).
set -euo pipefail

cd "$(dirname "$0")"

export PORT="${PORT:-8080}"

if command -v mvn >/dev/null 2>&1; then
  MVN=mvn
else
  echo "Maven (mvn) is required but was not found on PATH." >&2
  exit 1
fi

"$MVN" -B -q -DskipTests package

exec java -XX:+UseParallelGC -jar target/app.jar
