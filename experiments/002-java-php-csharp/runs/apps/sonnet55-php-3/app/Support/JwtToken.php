<?php

namespace App\Support;

use Firebase\JWT\JWT;
use Firebase\JWT\Key;

class JwtToken
{
    private const ALG = 'HS256';

    public static function issue(int $userId): string
    {
        $now = time();

        return JWT::encode([
            'sub' => $userId,
            'iat' => $now,
            'exp' => $now + 60 * 60 * 24 * 7,
        ], self::secret(), self::ALG);
    }

    /** Returns the user id from a valid token, or null when the token is invalid or expired. */
    public static function verify(string $token): ?int
    {
        try {
            $payload = JWT::decode($token, new Key(self::secret(), self::ALG));
        } catch (\Throwable) {
            return null;
        }

        return isset($payload->sub) ? (int) $payload->sub : null;
    }

    private static function secret(): string
    {
        return (string) config('app.key') ?: 'realworld-dev-secret-change-me-0123456789';
    }
}
