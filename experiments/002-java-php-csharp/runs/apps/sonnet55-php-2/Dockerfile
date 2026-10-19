# syntax=docker/dockerfile:1
FROM composer:2 AS vendor
WORKDIR /app
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-interaction --no-scripts --no-autoloader --prefer-dist --ignore-platform-reqs

FROM dunglas/frankenphp:php8.5
WORKDIR /app
RUN install-php-extensions pdo_sqlite pcntl
COPY --from=composer:2 /usr/bin/composer /usr/bin/composer
COPY --from=vendor /app/vendor ./vendor
COPY . .
# Release build: no dev dependencies, optimized autoloader, production env defaults.
RUN composer dump-autoload --no-dev --classmap-authoritative --no-interaction \
    && cp .env.example .env \
    && mkdir -p storage/framework/cache storage/framework/views storage/framework/sessions storage/logs bootstrap/cache database \
    && php artisan config:clear

ENV PORT=8000 \
    APP_ENV=production \
    APP_DEBUG=false \
    DB_DATABASE=/app/database/database.sqlite
EXPOSE 8000
# bin/serve.sh generates the app key if needed, creates the schema on an empty
# database, then runs Octane (FrankenPHP) with one worker per CPU core.
ENTRYPOINT ["/app/bin/serve.sh"]
