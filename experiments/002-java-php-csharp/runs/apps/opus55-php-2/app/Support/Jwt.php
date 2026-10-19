<?php

namespace App\Support;

use App\Models\User;
use Firebase\JWT\JWT as FirebaseJwt;
use Firebase\JWT\Key;
use Throwable;

class Jwt
{
    private const ALGORITHM = 'HS256';

    private const TTL_SECONDS = 60 * 60 * 24 * 30;

    public static function issue(User $user): string
    {
        $now = time();

        return FirebaseJwt::encode([
            'sub' => (string) $user->id,
            'username' => $user->username,
            'iat' => $now,
            'exp' => $now + self::TTL_SECONDS,
        ], self::secret(), self::ALGORITHM);
    }

    /** Returns the user id stored in the token, or null when the token is invalid or expired. */
    public static function userId(string $token): ?int
    {
        try {
            $payload = FirebaseJwt::decode($token, new Key(self::secret(), self::ALGORITHM));
        } catch (Throwable) {
            return null;
        }

        return isset($payload->sub) && ctype_digit((string) $payload->sub) ? (int) $payload->sub : null;
    }

    private static function secret(): string
    {
        return (string) (config('app.jwt_secret') ?: config('app.key'));
    }
}
