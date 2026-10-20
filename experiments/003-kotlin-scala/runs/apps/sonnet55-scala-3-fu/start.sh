#!/usr/bin/env sh
# Installs dependencies, builds a release (staged) distribution and starts the server in the foreground.
# Usage: PORT=8000 ./start.sh
set -eu
cd "$(dirname "$0")"

sbt -batch stage
exec ./scripts/run-server.sh
