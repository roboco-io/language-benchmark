# syntax=docker/dockerfile:1
FROM composer:2 AS vendor
WORKDIR /app
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-interaction --no-scripts --no-autoloader --prefer-dist \
        --ignore-platform-reqs

FROM dunglas/frankenphp:1-php8.5
RUN install-php-extensions pdo_sqlite pcntl opcache
ENV APP_ENV=production APP_DEBUG=false PORT=8000 \
    DB_DATABASE=/app/database/database.sqlite \
    PHP_INI_SCAN_DIR=":/usr/local/etc/php/app.conf.d"
COPY docker/php.ini /usr/local/etc/php/app.conf.d/zz-app.ini
WORKDIR /app
COPY --from=vendor /app/vendor ./vendor
COPY --from=vendor /usr/bin/composer /usr/bin/composer
COPY . .
RUN cp .env.example .env \
    && composer dump-autoload --no-dev --classmap-authoritative --no-interaction \
    && mkdir -p database storage/framework/cache storage/framework/sessions storage/framework/views storage/logs bootstrap/cache \
    && rm -f .env
EXPOSE 8000
ENTRYPOINT ["/app/docker/entrypoint.sh"]
