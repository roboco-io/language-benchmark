#!/usr/bin/env bash
# Installs dependencies, builds the optimized application caches, prepares the SQLite
# database and runs the server in the foreground (Laravel Octane on FrankenPHP).
# The port is read from $PORT (default 8000).
set -euo pipefail

cd "$(dirname "$0")"

composer install --no-dev --no-interaction --prefer-dist --optimize-autoloader --classmap-authoritative

exec ./docker/serve.sh
