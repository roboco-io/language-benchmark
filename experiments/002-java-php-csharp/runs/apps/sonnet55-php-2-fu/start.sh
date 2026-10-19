#!/usr/bin/env bash
# Installs dependencies, prepares the app and starts the server on $PORT (foreground).
set -euo pipefail
cd "$(dirname "$0")"

composer install --no-interaction --no-dev --optimize-autoloader --prefer-dist
exec bin/serve.sh
