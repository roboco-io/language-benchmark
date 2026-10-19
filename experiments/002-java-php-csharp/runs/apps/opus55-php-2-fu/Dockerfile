# Release image: Laravel Octane on FrankenPHP with SQLite.
FROM dunglas/frankenphp:1-php8.5 AS base

RUN install-php-extensions pcntl opcache zip \
    && cp "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini"

COPY docker/php.ini "$PHP_INI_DIR/conf.d/zz-app.ini"
COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

WORKDIR /app

# Install dependencies first so that this layer is cached across code changes.
FROM base AS vendor
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-interaction --no-progress --no-scripts --no-autoloader --prefer-dist

FROM base AS release
ENV APP_ENV=production \
    APP_DEBUG=false \
    LOG_CHANNEL=stderr \
    LOG_LEVEL=warning \
    DB_CONNECTION=sqlite \
    DB_DATABASE=/data/database.sqlite \
    PORT=8080

COPY --from=vendor /app/vendor ./vendor
COPY . .
RUN cp -n .env.example .env \
    && composer dump-autoload --no-dev --optimize --classmap-authoritative \
    && php artisan package:discover --ansi \
    && mkdir -p /data storage/framework/cache/data storage/framework/sessions storage/framework/views storage/logs \
    && chmod +x docker/entrypoint.sh \
    && rm /usr/bin/composer

VOLUME ["/data"]
EXPOSE 8080

ENTRYPOINT ["/app/docker/entrypoint.sh"]
