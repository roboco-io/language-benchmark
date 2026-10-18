#!/usr/bin/env bash
# Builds and installs Drogon (with SQLite support) from source on Linux.
# Used by start.sh and the Dockerfile when Drogon is not installed yet.
set -euo pipefail

DROGON_VERSION="${DROGON_VERSION:-v1.9.14}"
SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi

SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
git clone --depth 1 --branch "$DROGON_VERSION" --recurse-submodules --shallow-submodules \
  https://github.com/drogonframework/drogon.git "$SRC/drogon"
cmake -S "$SRC/drogon" -B "$SRC/build" -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_EXAMPLES=OFF -DBUILD_CTL=OFF -DBUILD_ORM=ON -DBUILD_SQLITE=ON \
  -DBUILD_POSTGRESQL=OFF -DBUILD_MYSQL=OFF -DBUILD_REDIS=OFF \
  -DBUILD_YAML_CONFIG=OFF -DBUILD_TESTING=OFF
cmake --build "$SRC/build" -j"$(nproc)"
$SUDO cmake --install "$SRC/build"
$SUDO ldconfig
