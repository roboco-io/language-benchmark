# syntax=docker/dockerfile:1

# ---- build stage: install dependencies into an isolated virtualenv ----
FROM python:3.13-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY app /srv/app/app
# Pre-compile optimized bytecode for the app and its dependencies.
RUN python -O -m compileall -q /srv/app/app /opt/venv

# ---- runtime stage: slim image with only the venv and the app ----
FROM python:3.13-slim
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONOPTIMIZE=1 \
    PORT=8000 \
    DATABASE_PATH=/data/realworld.db
RUN useradd --system --uid 10001 --home /srv/app app \
    && mkdir -p /data && chown app /data
COPY --from=build /opt/venv /opt/venv
COPY --from=build /srv/app /srv/app
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
WORKDIR /srv/app
USER app
VOLUME ["/data"]
EXPOSE 8000
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
