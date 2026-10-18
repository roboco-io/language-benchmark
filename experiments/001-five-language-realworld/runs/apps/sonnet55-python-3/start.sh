#!/usr/bin/env bash
# Install dependencies, build, and run the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-8000}"
export DATABASE_PATH="${DATABASE_PATH:-$PWD/realworld.db}"

if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
fi
.venv/bin/python -m pip install --quiet --disable-pip-version-check -r requirements.txt

# "Build": byte-compile the sources and create the schema once before workers start.
.venv/bin/python -m compileall -q app
.venv/bin/python -c "from app.db import init_db; init_db()"

WORKERS="$(.venv/bin/python -c 'import os; print(os.cpu_count() or 1)')"
echo "Starting server on port ${PORT} with ${WORKERS} workers"
exec .venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port "${PORT}" --workers "${WORKERS}"
