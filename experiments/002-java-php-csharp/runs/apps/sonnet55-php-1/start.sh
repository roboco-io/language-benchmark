#!/usr/bin/env bash
# Installs dependencies, prepares the app and runs it in the foreground
# on Laravel Octane (FrankenPHP) with one worker per CPU core.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"
export APP_ENV="${APP_ENV:-production}"
export APP_DEBUG="${APP_DEBUG:-false}"

cores() {
  nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1
}
WORKERS="${WORKERS:-$(cores)}"

composer install --no-interaction --no-dev --prefer-dist --optimize-autoloader

[ -f .env ] || cp .env.example .env
grep -q '^APP_KEY=.\+' .env || php artisan key:generate --force --no-interaction

mkdir -p database storage/framework/{cache,sessions,views} storage/logs bootstrap/cache
export DB_DATABASE="${DB_DATABASE:-$PWD/database/database.sqlite}"
[ -f "$DB_DATABASE" ] || touch "$DB_DATABASE"
php artisan migrate --force --no-interaction

if ! command -v frankenphp >/dev/null 2>&1 && [ ! -x ./frankenphp ]; then
  php artisan octane:install --server=frankenphp --no-interaction
fi

php artisan config:cache --no-interaction
php artisan route:cache --no-interaction

exec php artisan octane:start --server=frankenphp \
  --host=0.0.0.0 --port="$PORT" \
  --admin-port="${ADMIN_PORT:-0}" \
  --workers="$WORKERS" --max-requests="${MAX_REQUESTS:-0}" \
  --no-interaction
