<?php

return [
    // HMAC secret used to sign tokens. Falls back to APP_KEY when JWT_SECRET is empty.
    'secret' => env('JWT_SECRET') ?: env('APP_KEY'),
    'ttl' => (int) env('JWT_TTL', 86400),
];
