#!/usr/bin/env bash
# Installs dependencies, then runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-8000}"
WORKERS="${WORKERS:-$(python3 -c 'import os; print(os.cpu_count() or 1)')}"

if [ ! -d .venv ]; then
  python3 -m venv .venv
fi
.venv/bin/pip install --quiet --disable-pip-version-check -r requirements.txt

exec .venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --workers "$WORKERS" --log-level warning
