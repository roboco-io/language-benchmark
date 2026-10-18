#!/usr/bin/env bash
# Install dependencies, byte-compile the app, and run the server in the foreground.
set -euo pipefail

cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
VENV=".venv"

if [ ! -x "$VENV/bin/python" ]; then
  "$PYTHON" -m venv "$VENV"
fi
"$VENV/bin/python" -m pip install --quiet --disable-pip-version-check -r requirements.txt

# "Build" step: pre-compile bytecode so workers start fast.
"$VENV/bin/python" -m compileall -q app

PORT="${PORT:-8000}"
HOST="${HOST:-0.0.0.0}"
# One worker per available CPU core (respects CPU affinity where supported).
WORKERS="${WORKERS:-$("$VENV/bin/python" -c 'import os; print(len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else os.cpu_count() or 1)')}"

export DATABASE_PATH="${DATABASE_PATH:-data/realworld.db}"

echo "Starting RealWorld API on $HOST:$PORT with $WORKERS workers (db: $DATABASE_PATH)"
exec "$VENV/bin/python" -m uvicorn app.main:app \
  --host "$HOST" --port "$PORT" --workers "$WORKERS" \
  --no-access-log --proxy-headers
