<?php

namespace App\Support;

use Firebase\JWT\JWT as FirebaseJwt;
use Firebase\JWT\Key;
use Throwable;

/**
 * Issues and verifies HS256 JSON Web Tokens whose subject is the user id.
 */
class Jwt
{
    public static function issue(int $userId): string
    {
        $now = time();

        return FirebaseJwt::encode([
            'sub' => (string) $userId,
            'iat' => $now,
            'exp' => $now + config('jwt.ttl'),
        ], self::secret(), 'HS256');
    }

    /**
     * Returns the user id stored in a valid token, or null when the token is invalid or expired.
     */
    public static function verify(string $token): ?int
    {
        try {
            $payload = FirebaseJwt::decode($token, new Key(self::secret(), 'HS256'));
        } catch (Throwable) {
            return null;
        }

        return isset($payload->sub) && ctype_digit((string) $payload->sub) ? (int) $payload->sub : null;
    }

    private static function secret(): string
    {
        return (string) config('jwt.secret');
    }
}
