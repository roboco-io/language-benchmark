# syntax=docker/dockerfile:1

# ---- Build stage: install dependencies into a self-contained virtualenv ----
FROM python:3.14-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN python -m venv /opt/venv
COPY requirements.txt .
RUN /opt/venv/bin/pip install -r requirements.txt
COPY app /srv/app
# Release build: precompile optimized bytecode for the app and dependencies.
RUN /opt/venv/bin/python -O -m compileall -q /srv/app /opt/venv/lib

# ---- Runtime stage ----
FROM python:3.14-slim
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONOPTIMIZE=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8000 \
    DATABASE_PATH=/data/realworld.db
RUN useradd --system --uid 10001 app && mkdir /data && chown app /data
COPY --from=build /opt/venv /opt/venv
COPY --from=build /srv/app /srv/app
WORKDIR /srv
USER app
VOLUME ["/data"]
EXPOSE 8000
# app.serve creates the schema on an empty database, then starts one
# uvicorn worker per available CPU core on $PORT.
CMD ["python", "-m", "app.serve"]
