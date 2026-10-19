<?php

namespace App\Support;

use Firebase\JWT\JWT;
use Firebase\JWT\Key;
use Throwable;

class Token
{
    private const ALG = 'HS256';

    private const TTL = 60 * 60 * 24 * 7;

    private static function secret(): string
    {
        return hash('sha256', (string) config('app.key'), true);
    }

    public static function issue(int $userId): string
    {
        $now = time();

        return JWT::encode(['sub' => $userId, 'iat' => $now, 'exp' => $now + self::TTL], self::secret(), self::ALG);
    }

    /** Returns the user id, or null when the token is invalid or expired. */
    public static function verify(string $token): ?int
    {
        try {
            $claims = JWT::decode($token, new Key(self::secret(), self::ALG));
        } catch (Throwable) {
            return null;
        }

        return isset($claims->sub) ? (int) $claims->sub : null;
    }
}
