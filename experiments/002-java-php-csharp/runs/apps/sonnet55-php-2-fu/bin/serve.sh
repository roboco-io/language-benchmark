#!/usr/bin/env bash
# Prepares the environment, creates the schema and runs Octane (FrankenPHP) in the foreground.
# Used by start.sh (after composer install) and by the Docker image entrypoint.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${PORT:-8000}"

[ -f .env ] || cp .env.example .env
if ! grep -qE '^APP_KEY=.+' .env && [ -z "${APP_KEY:-}" ]; then
  php artisan key:generate --force --no-interaction >/dev/null
fi

DB_FILE="${DB_DATABASE:-database/database.sqlite}"
mkdir -p "$(dirname "$DB_FILE")" storage/framework/cache storage/framework/views storage/framework/sessions storage/logs bootstrap/cache
[ -f "$DB_FILE" ] || touch "$DB_FILE"
php artisan migrate --force --no-interaction

CORES="$( (nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN) | head -n1)"

exec php artisan octane:frankenphp \
  --host=0.0.0.0 \
  --port="$PORT" \
  --workers="$CORES" \
  --max-requests=1000000 \
  --admin-port="${ADMIN_PORT:-2019}"
