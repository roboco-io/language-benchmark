# syntax=docker/dockerfile:1
FROM composer:2 AS vendor
WORKDIR /app
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-interaction --no-scripts --no-autoloader --prefer-dist --ignore-platform-reqs
COPY . .
RUN composer dump-autoload --no-dev --optimize --classmap-authoritative --no-scripts --ignore-platform-reqs

FROM dunglas/frankenphp:php8.5
WORKDIR /app
RUN install-php-extensions pdo_sqlite pcntl opcache
COPY --from=vendor /app /app
RUN cp .env.example .env \
    && php artisan key:generate --force \
    && php artisan package:discover --ansi \
    && mkdir -p storage/framework/cache storage/framework/sessions storage/framework/views storage/logs database \
    && chmod -R a+rwX storage bootstrap/cache database

ENV APP_ENV=production \
    APP_DEBUG=false \
    PORT=8000 \
    DB_DATABASE=/app/database/database.sqlite \
    PHP_INI_SCAN_DIR=":/usr/local/etc/php/conf.d"

EXPOSE 8000
CMD ["sh", "-c", "touch \"$DB_DATABASE\" && php artisan migrate --force --no-interaction && php artisan config:cache && php artisan route:cache && exec php artisan octane:start --server=frankenphp --host=0.0.0.0 --port=\"$PORT\" --workers=\"$(nproc)\" --max-requests=5000"]
