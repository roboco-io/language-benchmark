#!/usr/bin/env bash
# Builds the application (Maven downloads dependencies on first run) and starts it in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8080}"

mvn -B -q -DskipTests clean package

exec java -jar target/app.jar
