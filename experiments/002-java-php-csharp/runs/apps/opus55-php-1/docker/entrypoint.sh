#!/bin/sh
# Container entrypoint: prepares config and schema, then runs Octane on $PORT
# with one FrankenPHP worker per available CPU core.
set -eu

cd /app

PORT="${PORT:-8000}"
WORKERS="$(nproc)"

# Generate an application key once if none was provided.
if [ -z "${APP_KEY:-}" ] && ! grep -qE '^APP_KEY=.+' .env; then
  php artisan key:generate --force --no-interaction >/dev/null
fi

mkdir -p "$(dirname "$DB_DATABASE")"
touch "$DB_DATABASE"

php artisan config:cache >/dev/null
php artisan route:cache >/dev/null
# Creates the schema on an empty database; a no-op when it is already up to date.
php artisan migrate --force --no-interaction

echo "Starting RealWorld API on 0.0.0.0:${PORT} with ${WORKERS} workers"
exec php artisan octane:start \
  --server=frankenphp \
  --host=0.0.0.0 \
  --port="$PORT" \
  --workers="$WORKERS" \
  --max-requests=10000
