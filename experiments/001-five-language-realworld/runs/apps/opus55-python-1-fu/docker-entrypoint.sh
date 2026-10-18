#!/bin/sh
# Run uvicorn with one worker per available CPU core. The schema is created
# by the application on startup if the database is empty.
set -e
WORKERS="${WORKERS:-$(python -c 'import os; print(len(os.sched_getaffinity(0)))')}"
exec python -O -m uvicorn app.main:app \
  --host 0.0.0.0 --port "${PORT:-8000}" --workers "$WORKERS" \
  --no-access-log --proxy-headers
