#!/usr/bin/env bash
# Installs dependencies, builds the server in Release mode, and runs it in the
# foreground. The listening port comes from the PORT environment variable.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export PORT="${PORT:-8000}"

SUDO=""
if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then SUDO="sudo"; fi

install_deps() {
  case "$(uname -s)" in
    Darwin)
      command -v brew >/dev/null 2>&1 || { echo "Homebrew is required on macOS" >&2; exit 1; }
      for pkg in cmake drogon openssl@3 sqlite; do
        brew list "$pkg" >/dev/null 2>&1 || brew install "$pkg"
      done
      ;;
    Linux)
      if command -v apt-get >/dev/null 2>&1; then
        if ! dpkg -s libssl-dev libsqlite3-dev libjsoncpp-dev uuid-dev zlib1g-dev cmake g++ git >/dev/null 2>&1; then
          $SUDO apt-get update
          $SUDO apt-get install -y --no-install-recommends build-essential cmake git pkg-config \
            libssl-dev libsqlite3-dev libjsoncpp-dev uuid-dev zlib1g-dev ca-certificates
        fi
      fi
      if [ ! -f /usr/local/lib/cmake/Drogon/DrogonConfig.cmake ] && [ ! -f /usr/lib/cmake/Drogon/DrogonConfig.cmake ]; then
        "$ROOT/scripts/install-drogon-linux.sh"
      fi
      ;;
  esac
}

install_deps

JOBS="$( (nproc 2>/dev/null || sysctl -n hw.ncpu) )"
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j"$JOBS"

echo "Starting server on port $PORT"
exec ./build/realworld
