#!/usr/bin/env bash
# Builds the application (resolving Maven dependencies) and runs it in the foreground.
# The listening port comes from the PORT environment variable (default 8080).
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8080}"

mvn -q -B -DskipTests package
exec java -XX:+UseSerialGC -XX:TieredStopAtLevel=4 -jar target/realworld.jar
