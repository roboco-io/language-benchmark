#!/usr/bin/env bash
# Installs dependencies (if missing), builds a release binary and runs the
# server in the foreground. The listening port comes from $PORT (default 8000).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

DEPS_PREFIX="$ROOT/.deps"
DROGON_VERSION="v1.9.14"
CMAKE_ARGS=(-DCMAKE_BUILD_TYPE=Release)

install_deps_macos() {
  command -v brew >/dev/null || { echo "Homebrew is required on macOS" >&2; exit 1; }
  local pkgs=(cmake drogon jsoncpp openssl@3 sqlite)
  for p in "${pkgs[@]}"; do
    brew list --versions "$p" >/dev/null 2>&1 || brew install "$p"
  done
  CMAKE_ARGS+=(-DOPENSSL_ROOT_DIR="$(brew --prefix openssl@3)")
}

install_deps_linux() {
  if command -v apt-get >/dev/null; then
    local sudo=""
    [ "$(id -u)" -ne 0 ] && sudo="sudo"
    local pkgs=(build-essential cmake git libjsoncpp-dev libssl-dev libsqlite3-dev uuid-dev zlib1g-dev)
    local missing=()
    for p in "${pkgs[@]}"; do
      dpkg -s "$p" >/dev/null 2>&1 || missing+=("$p")
    done
    if [ ${#missing[@]} -gt 0 ]; then
      $sudo apt-get update && $sudo apt-get install -y --no-install-recommends "${missing[@]}"
    fi
  fi
  # Drogon is not packaged on most distributions; build it into .deps once.
  if [ ! -f "$DEPS_PREFIX/lib/cmake/Drogon/DrogonConfig.cmake" ]; then
    local src="$DEPS_PREFIX/src/drogon"
    rm -rf "$src"
    git clone --depth 1 --branch "$DROGON_VERSION" --recurse-submodules \
      https://github.com/drogonframework/drogon.git "$src"
    cmake -S "$src" -B "$src/build" -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" -DBUILD_EXAMPLES=OFF -DBUILD_CTL=OFF \
      -DBUILD_POSTGRESQL=OFF -DBUILD_MYSQL=OFF -DBUILD_REDIS=OFF -DBUILD_SQLITE=ON
    cmake --build "$src/build" -j "$(nproc)"
    cmake --install "$src/build"
  fi
  CMAKE_ARGS+=(-DCMAKE_PREFIX_PATH="$DEPS_PREFIX")
}

case "$(uname -s)" in
  Darwin) install_deps_macos ;;
  Linux) install_deps_linux ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

cmake -S . -B build "${CMAKE_ARGS[@]}"
cmake --build build -j

export PORT="${PORT:-8000}"
exec ./build/realworld
