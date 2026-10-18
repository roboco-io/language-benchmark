# syntax=docker/dockerfile:1

# ---- build stage: install dependencies into an isolated virtualenv ----
FROM python:3.14-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
RUN python -m venv /opt/venv
COPY requirements.txt .
RUN /opt/venv/bin/pip install -r requirements.txt
COPY app ./app
# Precompile optimized bytecode for the app and its dependencies.
RUN /opt/venv/bin/python -O -m compileall -q /app/app /opt/venv/lib

# ---- runtime stage: minimal image with the prebuilt venv and app ----
FROM python:3.14-slim
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONOPTIMIZE=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8000 \
    DATABASE_PATH=/data/realworld.db
RUN useradd --system --uid 10001 app && mkdir -p /data && chown app /data
WORKDIR /app
COPY --from=build /opt/venv /opt/venv
COPY --from=build /app/app ./app
USER app
VOLUME ["/data"]
EXPOSE 8000
# app.serve creates the schema on an empty database, then starts uvicorn
# with one worker per available CPU core on $PORT.
CMD ["python", "-m", "app.serve"]
