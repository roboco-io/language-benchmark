#!/usr/bin/env bash
# Installs dependencies, prepares the SQLite database, and runs Octane (FrankenPHP) in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"
export APP_ENV="${APP_ENV:-production}"
export APP_DEBUG="${APP_DEBUG:-false}"
export DB_DATABASE="${DB_DATABASE:-$PWD/database/database.sqlite}"

[ -f .env ] || cp .env.example .env
composer install --no-interaction --prefer-dist --optimize-autoloader --no-dev
grep -q '^APP_KEY=.\+' .env || php artisan key:generate --force --no-interaction

mkdir -p "$(dirname "$DB_DATABASE")" storage/framework/{cache,sessions,views} storage/logs
touch "$DB_DATABASE"
php artisan migrate --force --no-interaction
php artisan config:cache --no-interaction
php artisan route:cache --no-interaction

WORKERS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
exec php artisan octane:start --server=frankenphp --host=0.0.0.0 --port="$PORT" --workers="$WORKERS" --max-requests=5000
