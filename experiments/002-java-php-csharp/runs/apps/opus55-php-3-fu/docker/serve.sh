#!/usr/bin/env bash
# Creates the SQLite schema when needed, caches config/routes and starts Laravel Octane on
# FrankenPHP in the foreground with one worker per available CPU core.
set -euo pipefail

cd "$(dirname "$0")/.."

# Create .env with a random APP_KEY (also used as the JWT secret) unless one is provided.
if [ ! -f .env ]; then
    cp .env.example .env
fi
if [ -z "${APP_KEY:-}" ] && ! grep -qE '^APP_KEY=.+' .env; then
    php artisan key:generate --force --no-interaction
fi

DB_DATABASE="${DB_DATABASE:-$PWD/database/database.sqlite}"
export DB_DATABASE
mkdir -p "$(dirname "$DB_DATABASE")"
[ -f "$DB_DATABASE" ] || touch "$DB_DATABASE"

php artisan config:cache --no-interaction
php artisan route:cache --no-interaction
php artisan event:cache --no-interaction
php artisan migrate --force --no-interaction

if command -v nproc >/dev/null 2>&1; then
    WORKERS="$(nproc)"
else
    WORKERS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)"
fi

echo "Starting Octane (FrankenPHP) on port ${PORT:-8000} with ${WORKERS} workers"
exec php artisan octane:frankenphp \
    --host="${HOST:-0.0.0.0}" \
    --port="${PORT:-8000}" \
    --workers="${WORKERS}" \
    --max-requests="${OCTANE_MAX_REQUESTS:-10000}" \
    --log-level="${OCTANE_LOG_LEVEL:-WARN}"
