#!/usr/bin/env sh
# Launches the staged Play distribution in the foreground.
# PORT selects the listening port; worker threads are sized to the available CPU cores.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${APP_BIN:-$ROOT/target/universal/stage/bin/realworld}"

if command -v nproc >/dev/null 2>&1; then CORES="$(nproc)"; else CORES="$(sysctl -n hw.ncpu)"; fi

export PORT="${PORT:-8000}"
# The staged launcher changes into its own directory, so default the database to an absolute path.
export DB_PATH="${DB_PATH:-$ROOT/data/realworld.db}"
# Pekko's TCP selector pool (network I/O) also gets one thread per core; the default
# dispatcher is sized the same way in conf/application.conf.
JAVA_OPTS="${JAVA_OPTS:-} -Dpekko.io.tcp.nr-of-selectors=$CORES -XX:+UseParallelGC"
export JAVA_OPTS

exec "$BIN" -Dhttp.port="$PORT"
