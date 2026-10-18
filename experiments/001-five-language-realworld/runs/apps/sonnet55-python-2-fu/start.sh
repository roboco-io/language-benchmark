#!/usr/bin/env bash
# Installs dependencies into a local virtualenv and starts the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
if [ ! -d .venv ]; then
  "$PYTHON" -m venv .venv
fi
.venv/bin/pip install --quiet --disable-pip-version-check -r requirements.txt

export PORT="${PORT:-8000}"
exec .venv/bin/python run.py
