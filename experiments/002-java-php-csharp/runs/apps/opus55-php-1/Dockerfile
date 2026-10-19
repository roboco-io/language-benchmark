# syntax=docker/dockerfile:1

# ---- Build stage: install production dependencies with an optimized autoloader ----
FROM dunglas/frankenphp:php8.5 AS build

RUN install-php-extensions pcntl pdo_sqlite zip
COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

WORKDIR /app
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-scripts --no-autoloader --prefer-dist --no-interaction --no-progress

COPY . .
RUN rm -rf .env database/*.sqlite* bootstrap/cache/*.php \
 && composer dump-autoload --no-dev --optimize --classmap-authoritative \
 && php artisan package:discover --ansi

# ---- Runtime stage ----
FROM dunglas/frankenphp:php8.5

RUN install-php-extensions pcntl pdo_sqlite opcache \
 && mv "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini" \
 && printf 'opcache.enable_cli=1\nopcache.validate_timestamps=0\nopcache.memory_consumption=128\n' > "$PHP_INI_DIR/conf.d/zz-opcache.ini"

WORKDIR /app
COPY --from=build /app /app
RUN cp .env.example .env \
 && mkdir -p database storage/framework/cache storage/framework/views storage/framework/sessions storage/logs bootstrap/cache \
 && chmod +x docker/entrypoint.sh

ENV PORT=8000 \
    APP_ENV=production \
    APP_DEBUG=false \
    DB_DATABASE=/app/database/database.sqlite

EXPOSE 8000
ENTRYPOINT ["/app/docker/entrypoint.sh"]
