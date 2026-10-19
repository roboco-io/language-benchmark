# syntax=docker/dockerfile:1

# ---- Build stage: install production dependencies with an optimized autoloader ----
FROM dunglas/frankenphp:1-php8.5 AS build

RUN install-php-extensions pcntl zip
COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

WORKDIR /app
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-interaction --prefer-dist --no-scripts --no-autoloader

COPY . .
RUN composer dump-autoload --no-dev --optimize --classmap-authoritative \
    && composer run-script post-autoload-dump --no-dev

# ---- Runtime stage ----
FROM dunglas/frankenphp:1-php8.5

RUN install-php-extensions pcntl \
    && cp "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini"

WORKDIR /app
COPY --from=build /app /app

ENV APP_ENV=production \
    APP_DEBUG=false \
    DB_DATABASE=/app/data/database.sqlite \
    PORT=8000

RUN mkdir -p /app/data \
    && rm -f /app/.env \
    && chmod +x /app/docker/serve.sh

VOLUME ["/app/data"]
EXPOSE 8000

# serve.sh creates the schema on an empty database and starts Octane with one worker per core.
CMD ["/app/docker/serve.sh"]
