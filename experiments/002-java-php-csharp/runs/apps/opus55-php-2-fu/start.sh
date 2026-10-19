#!/usr/bin/env bash
# Installs dependencies, prepares the app and the SQLite schema, then runs the
# server in the foreground with Laravel Octane on FrankenPHP (one worker per CPU core).
set -euo pipefail

cd "$(dirname "$0")"

PORT="${PORT:-8000}"

cpu_count() {
  nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1
}
WORKERS="${WORKERS:-$(cpu_count)}"

composer install --no-interaction --prefer-dist --optimize-autoloader --no-dev --no-progress

if [ ! -f .env ]; then
  cp .env.example .env
fi
if ! grep -qE '^APP_KEY=.+' .env; then
  php artisan key:generate --force --no-interaction
fi

DB_FILE="${DB_DATABASE:-database/database.sqlite}"
mkdir -p "$(dirname "$DB_FILE")"
touch "$DB_FILE"

php artisan optimize:clear >/dev/null
php artisan migrate --force --no-interaction
php artisan optimize

# The Caddy admin endpoint must not clash with the HTTP port.
ADMIN_PORT="${ADMIN_PORT:-2019}"
if [ "$ADMIN_PORT" = "$PORT" ]; then
  ADMIN_PORT=$((PORT + 1))
fi

echo "Starting Octane (FrankenPHP) on port $PORT with $WORKERS workers"
exec php artisan octane:start \
  --server=frankenphp \
  --host=0.0.0.0 \
  --port="$PORT" \
  --admin-port="$ADMIN_PORT" \
  --workers="$WORKERS" \
  --max-requests=10000
