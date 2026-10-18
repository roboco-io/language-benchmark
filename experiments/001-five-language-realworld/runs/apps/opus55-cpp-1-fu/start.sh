#!/usr/bin/env bash
# Installs dependencies (if missing), builds a release binary, and runs the
# RealWorld API server in the foreground. The port comes from $PORT (default 8080).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
DROGON_VERSION="v1.9.14"
DEPS_PREFIX="$ROOT/.deps"

have_drogon() {
  # Succeeds if CMake can locate an installed Drogon package.
  local probe
  probe="$(mktemp -d)"
  cat > "$probe/CMakeLists.txt" <<'CMAKE'
cmake_minimum_required(VERSION 3.16)
project(probe CXX)
find_package(Drogon CONFIG REQUIRED)
CMAKE
  cmake -S "$probe" -B "$probe/build" -DCMAKE_PREFIX_PATH="$DEPS_PREFIX" >/dev/null 2>&1
  local rc=$?
  rm -rf "$probe"
  return $rc
}

install_deps() {
  case "$(uname -s)" in
    Darwin)
      command -v brew >/dev/null || { echo "Homebrew is required on macOS" >&2; exit 1; }
      brew list drogon >/dev/null 2>&1 || brew install drogon
      ;;
    Linux)
      if command -v apt-get >/dev/null; then
        SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
        $SUDO apt-get update
        $SUDO apt-get install -y --no-install-recommends git cmake make g++ \
          libjsoncpp-dev uuid-dev zlib1g-dev libssl-dev libsqlite3-dev
      fi
      local src
      src="$(mktemp -d)"
      git clone --depth 1 --branch "$DROGON_VERSION" --recurse-submodules \
        https://github.com/drogonframework/drogon.git "$src/drogon"
      cmake -S "$src/drogon" -B "$src/drogon/build" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" -DBUILD_EXAMPLES=OFF -DBUILD_CTL=OFF \
        -DBUILD_ORM=ON -DBUILD_POSTGRESQL=OFF -DBUILD_MYSQL=OFF -DBUILD_SQLITE=ON \
        -DBUILD_REDIS=OFF -DBUILD_TESTING=OFF
      cmake --build "$src/drogon/build" -j"$(nproc)"
      cmake --install "$src/drogon/build"
      rm -rf "$src"
      ;;
    *)
      echo "Unsupported platform: $(uname -s)" >&2; exit 1
      ;;
  esac
}

have_drogon || install_deps

JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$DEPS_PREFIX"
cmake --build build -j"$JOBS"

export PORT="${PORT:-8080}"
exec ./build/realworld
