#!/bin/sh
# Container entrypoint: prepares secrets and the SQLite schema, then runs
# Laravel Octane on FrankenPHP in the foreground with one worker per CPU core.
set -eu

cd /app

PORT="${PORT:-8080}"
WORKERS="${WORKERS:-$(nproc)}"

# Without an APP_KEY, generate one for this container. JWTs signed with it stay
# valid for the lifetime of the container; pass APP_KEY or JWT_SECRET to keep
# them valid across restarts.
if [ -z "${APP_KEY:-}" ]; then
  APP_KEY="base64:$(php -r 'echo base64_encode(random_bytes(32));')"
  export APP_KEY
fi

mkdir -p "$(dirname "$DB_DATABASE")"
touch "$DB_DATABASE"

# Creates the schema on an empty database; a no-op when it is up to date.
php artisan migrate --force --no-interaction
php artisan optimize

ADMIN_PORT="${ADMIN_PORT:-2019}"
if [ "$ADMIN_PORT" = "$PORT" ]; then
  ADMIN_PORT=$((PORT + 1))
fi

exec php artisan octane:start \
  --server=frankenphp \
  --host=0.0.0.0 \
  --port="$PORT" \
  --admin-port="$ADMIN_PORT" \
  --workers="$WORKERS" \
  --max-requests=10000
