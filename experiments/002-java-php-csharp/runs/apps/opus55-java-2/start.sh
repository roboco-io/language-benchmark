#!/usr/bin/env bash
# Installs dependencies, builds the application and runs it in the foreground.
# The HTTP port is taken from the PORT environment variable (default 8080).
set -euo pipefail

cd "$(dirname "$0")"

export PORT="${PORT:-8080}"

if [ -x ./mvnw ]; then MVN=./mvnw; else MVN=mvn; fi

# Resolve dependencies and build the executable jar (tests are run separately via Hurl).
"$MVN" -B -q -DskipTests package

exec java --enable-native-access=ALL-UNNAMED -jar target/app.jar
