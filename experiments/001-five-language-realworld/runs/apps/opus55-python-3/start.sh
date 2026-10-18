#!/usr/bin/env bash
# Install dependencies into a local virtualenv, then run the server in the
# foreground. The port is read from $PORT (default 8000).
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
if [ ! -x .venv/bin/python ]; then
  "$PYTHON" -m venv .venv
fi
.venv/bin/pip install --quiet --disable-pip-version-check -r requirements.txt
# "Build" step: byte-compile the application sources.
.venv/bin/python -m compileall -q app

exec .venv/bin/python -m app.serve
