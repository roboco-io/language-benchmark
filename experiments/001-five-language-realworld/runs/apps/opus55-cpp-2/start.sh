#!/usr/bin/env bash
# Installs dependencies, builds the server in Release mode and runs it in the
# foreground. The listening port comes from the PORT environment variable.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

DROGON_VERSION="v1.9.14"
DEPS_DIR="$ROOT/.deps"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
CMAKE_ARGS=()

have_drogon() {
  # Drogon is usable when CMake can find its package config.
  local probe
  probe="$(mktemp -d)"
  cat > "$probe/CMakeLists.txt" <<'CMAKE'
cmake_minimum_required(VERSION 3.16)
project(probe CXX)
find_package(Drogon CONFIG REQUIRED)
CMAKE
  if cmake -S "$probe" -B "$probe/build" "$@" >/dev/null 2>&1; then
    rm -rf "$probe"
    return 0
  fi
  rm -rf "$probe"
  return 1
}

build_drogon_from_source() {
  echo "==> Building Drogon $DROGON_VERSION from source into $DEPS_DIR"
  mkdir -p "$DEPS_DIR"
  if [ ! -d "$DEPS_DIR/drogon" ]; then
    git clone --depth 1 --branch "$DROGON_VERSION" --recurse-submodules \
      https://github.com/drogonframework/drogon "$DEPS_DIR/drogon"
  fi
  cmake -S "$DEPS_DIR/drogon" -B "$DEPS_DIR/drogon/build" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 \
    -DCMAKE_INSTALL_PREFIX="$DEPS_DIR/install" \
    -DBUILD_EXAMPLES=OFF -DBUILD_CTL=OFF -DBUILD_TESTING=OFF \
    -DBUILD_ORM=ON -DBUILD_SQLITE=ON -DBUILD_POSTGRESQL=OFF \
    -DBUILD_MYSQL=OFF -DBUILD_REDIS=OFF -DBUILD_SHARED_LIBS=OFF
  cmake --build "$DEPS_DIR/drogon/build" -j "$JOBS"
  cmake --install "$DEPS_DIR/drogon/build"
}

install_dependencies() {
  if [ -d "$DEPS_DIR/install" ]; then
    CMAKE_ARGS+=("-DCMAKE_PREFIX_PATH=$DEPS_DIR/install")
  fi
  if have_drogon ${CMAKE_ARGS[@]+"${CMAKE_ARGS[@]}"}; then
    echo "==> Drogon found"
    return
  fi

  case "$(uname -s)" in
    Darwin)
      echo "==> Installing Drogon with Homebrew"
      brew install drogon
      ;;
    Linux)
      if command -v apt-get >/dev/null 2>&1; then
        echo "==> Installing build dependencies with apt-get"
        local sudo=""
        [ "$(id -u)" -ne 0 ] && sudo="sudo"
        $sudo apt-get update
        $sudo apt-get install -y --no-install-recommends \
          build-essential cmake git ca-certificates pkg-config \
          libjsoncpp-dev uuid-dev zlib1g-dev libssl-dev libsqlite3-dev \
          libbrotli-dev libc-ares-dev
      fi
      build_drogon_from_source
      CMAKE_ARGS=("-DCMAKE_PREFIX_PATH=$DEPS_DIR/install")
      ;;
    *)
      echo "Unsupported platform: $(uname -s)" >&2
      exit 1
      ;;
  esac
}

install_dependencies

echo "==> Building (Release)"
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release ${CMAKE_ARGS[@]+"${CMAKE_ARGS[@]}"}
cmake --build build -j "$JOBS"

export PORT="${PORT:-8080}"
export DATABASE_PATH="${DATABASE_PATH:-$ROOT/data/realworld.db}"
echo "==> Starting server on port $PORT"
exec ./build/realworld
