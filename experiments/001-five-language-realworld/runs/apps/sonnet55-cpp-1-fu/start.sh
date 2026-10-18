#!/usr/bin/env bash
# Installs dependencies (if missing), builds a release binary and runs the server in the foreground.
# Environment: PORT (default 8080), DATABASE_PATH (default ./realworld.db), JWT_SECRET.
set -euo pipefail
cd "$(dirname "$0")"

have_drogon() {
  [ -f /opt/homebrew/lib/cmake/Drogon/DrogonConfig.cmake ] ||
  [ -f /usr/local/lib/cmake/Drogon/DrogonConfig.cmake ] ||
  [ -f /usr/lib/cmake/Drogon/DrogonConfig.cmake ] ||
  ls /usr/lib/*/cmake/Drogon/DrogonConfig.cmake >/dev/null 2>&1
}

install_deps() {
  case "$(uname -s)" in
    Darwin)
      command -v brew >/dev/null || { echo "Homebrew is required to install dependencies" >&2; exit 1; }
      brew list drogon >/dev/null 2>&1 || brew install drogon
      brew list sqlite >/dev/null 2>&1 || brew install sqlite
      command -v cmake >/dev/null || brew install cmake
      ;;
    Linux)
      local sudo=""
      [ "$(id -u)" -eq 0 ] || sudo="sudo"
      if command -v apt-get >/dev/null; then
        $sudo apt-get update
        $sudo apt-get install -y --no-install-recommends build-essential cmake pkg-config \
          libdrogon-dev libsqlite3-dev libssl-dev libjsoncpp-dev uuid-dev zlib1g-dev \
          libpq-dev libmariadb-dev libhiredis-dev libbrotli-dev libyaml-cpp-dev libc-ares-dev
      else
        echo "Unsupported package manager: install Drogon, SQLite3, OpenSSL and CMake manually" >&2
        exit 1
      fi
      ;;
  esac
}

if ! have_drogon || ! command -v cmake >/dev/null; then
  install_deps
fi

PREFIXES="/opt/homebrew;/usr/local"
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$PREFIXES" >/dev/null
cmake --build build --parallel "$(getconf _NPROCESSORS_ONLN)"

export PORT="${PORT:-8080}"
echo "Starting server on port $PORT"
exec ./build/realworld
