#!/usr/bin/env bash
# Install dependencies, prepare the app, and run the RealWorld API server in the foreground.
# The listening port is taken from $PORT (default 8000).
set -euo pipefail

cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
VENV_DIR="${VENV_DIR:-.venv}"

if [ ! -x "$VENV_DIR/bin/python" ]; then
  "$PYTHON" -m venv "$VENV_DIR"
fi

"$VENV_DIR/bin/python" -m pip install --quiet --disable-pip-version-check -r requirements.txt

# "Build" step: byte-compile the application ahead of time.
"$VENV_DIR/bin/python" -m compileall -q app

export PORT="${PORT:-8000}"
exec "$VENV_DIR/bin/python" -m app.serve
