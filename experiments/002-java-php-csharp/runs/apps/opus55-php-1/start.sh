#!/usr/bin/env bash
# Installs dependencies, prepares the database, and runs the API server in the foreground.
# The server listens on $PORT (default 8000) with one Octane worker per CPU core.
set -euo pipefail

cd "$(dirname "$0")"

PORT="${PORT:-8000}"
HOST="${HOST_BIND:-0.0.0.0}"

if command -v nproc >/dev/null 2>&1; then
  WORKERS="$(nproc)"
else
  WORKERS="$(sysctl -n hw.ncpu 2>/dev/null || echo 1)"
fi

composer install --no-dev --optimize-autoloader --classmap-authoritative --no-interaction --no-progress

if [ ! -f .env ]; then
  cp .env.example .env
fi
if ! grep -qE '^APP_KEY=.+' .env; then
  php artisan key:generate --force --no-interaction
fi

mkdir -p database storage/framework/{cache,views,sessions} storage/logs bootstrap/cache
touch database/database.sqlite

php artisan optimize:clear >/dev/null
php artisan config:cache
php artisan route:cache
php artisan migrate --force --no-interaction

echo "Starting RealWorld API on ${HOST}:${PORT} with ${WORKERS} workers"
exec php artisan octane:start \
  --server=frankenphp \
  --host="$HOST" \
  --port="$PORT" \
  --workers="$WORKERS" \
  --max-requests=10000
