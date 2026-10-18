#!/usr/bin/env bash
# Installs the libraries the server needs (Drogon, OpenSSL, SQLite, JsonCpp).
# Compilers, CMake and language runtimes are expected to exist already.
# Idempotent: does nothing when Drogon is already installed.
set -euo pipefail

DROGON_VERSION="${DROGON_VERSION:-v1.9.14}"

has_drogon() {
  command -v drogon_ctl >/dev/null 2>&1 && return 0
  [ -f /usr/local/lib/cmake/Drogon/DrogonConfig.cmake ] && return 0
  [ -f /usr/local/lib64/cmake/Drogon/DrogonConfig.cmake ] && return 0
  return 1
}

if has_drogon; then
  echo "[deps] Drogon already installed"
  exit 0
fi

SUDO=""
if [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1; then SUDO="sudo"; fi

case "$(uname -s)" in
  Darwin)
    echo "[deps] Installing Drogon with Homebrew"
    brew install drogon
    ;;
  Linux)
    echo "[deps] Installing Drogon build dependencies with apt and building Drogon $DROGON_VERSION"
    export DEBIAN_FRONTEND=noninteractive
    $SUDO apt-get update
    $SUDO apt-get install -y --no-install-recommends \
      build-essential cmake git ca-certificates pkg-config \
      libjsoncpp-dev uuid-dev zlib1g-dev libssl-dev libsqlite3-dev libc-ares-dev libbrotli-dev
    src="$(mktemp -d)"
    git clone --depth 1 --branch "$DROGON_VERSION" --recurse-submodules --shallow-submodules \
      https://github.com/drogonframework/drogon.git "$src/drogon"
    cmake -S "$src/drogon" -B "$src/build" -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 \
      -DBUILD_EXAMPLES=OFF -DBUILD_CTL=ON -DBUILD_ORM=ON -DBUILD_SQLITE=ON \
      -DBUILD_POSTGRESQL=OFF -DBUILD_MYSQL=OFF -DBUILD_REDIS=OFF -DBUILD_YAML_CONFIG=OFF
    cmake --build "$src/build" -j"$(nproc)"
    $SUDO cmake --install "$src/build"
    $SUDO ldconfig || true
    rm -rf "$src"
    ;;
  *)
    echo "[deps] Unsupported OS: $(uname -s)" >&2
    exit 1
    ;;
esac
