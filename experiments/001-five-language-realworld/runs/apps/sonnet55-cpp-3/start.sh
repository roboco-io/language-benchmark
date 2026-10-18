#!/usr/bin/env bash
# Installs dependencies, builds the server in Release mode and runs it in the foreground.
# The listening port is read from the PORT environment variable (default 8080).
set -euo pipefail
cd "$(dirname "$0")"

./scripts/install-deps.sh

cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"

export PORT="${PORT:-8080}"
exec ./build/realworld
