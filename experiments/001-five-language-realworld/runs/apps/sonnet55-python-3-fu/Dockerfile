FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=0 \
    PYTHONUNBUFFERED=1 \
    PORT=8000 \
    DATABASE_PATH=/data/realworld.db

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
# Release build: pre-compile bytecode.
RUN python -m compileall -q app && mkdir -p /data

EXPOSE 8000
# The schema is created at startup (before workers spawn) when the database is empty.
CMD ["sh", "-c", "python -c 'from app.db import init_db; init_db()' && exec python -m uvicorn app.main:app --host 0.0.0.0 --port \"$PORT\" --workers \"$(python -c 'import os; print(os.cpu_count() or 1)')\""]
