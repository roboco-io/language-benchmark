#!/bin/sh
# Prepares the database and runs Octane (FrankenPHP) with one worker per CPU core.
set -eu
cd /app

[ -f .env ] || cp .env.example .env
if ! grep -q '^APP_KEY=.\+' .env; then
  php artisan key:generate --force --no-interaction >/dev/null
fi

mkdir -p "$(dirname "$DB_DATABASE")"
[ -f "$DB_DATABASE" ] || touch "$DB_DATABASE"
php artisan migrate --force --no-interaction

php artisan config:cache --no-interaction
php artisan route:cache --no-interaction

exec php artisan octane:start --server=frankenphp \
  --host=0.0.0.0 --port="${PORT:-8000}" --admin-port="${ADMIN_PORT:-0}" \
  --workers="${WORKERS:-$(nproc)}" --max-requests="${MAX_REQUESTS:-0}" --no-interaction
