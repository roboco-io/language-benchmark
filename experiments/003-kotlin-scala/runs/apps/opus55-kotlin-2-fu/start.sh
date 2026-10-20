#!/usr/bin/env bash
# Installs dependencies, builds the release jar, and runs the server in the foreground.
# The server listens on $PORT (default 8080) and stores data in $DB_PATH (default ./data/realworld.db).
set -euo pipefail

cd "$(dirname "$0")"

gradle --no-daemon -q bootJar

export PORT="${PORT:-8080}"
exec java -XX:+UseParallelGC -jar build/libs/realworld.jar
