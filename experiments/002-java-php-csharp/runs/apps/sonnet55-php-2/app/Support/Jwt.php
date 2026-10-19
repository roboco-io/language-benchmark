<?php

namespace App\Support;

/** Minimal HS256 JSON Web Token encoder/decoder. */
class Jwt
{
    private const TTL = 60 * 60 * 24 * 30;

    public static function encode(int $userId): string
    {
        $now = time();
        $header = self::b64(json_encode(['typ' => 'JWT', 'alg' => 'HS256']));
        $payload = self::b64(json_encode(['sub' => $userId, 'iat' => $now, 'exp' => $now + self::TTL]));

        return "$header.$payload.".self::b64(hash_hmac('sha256', "$header.$payload", self::secret(), true));
    }

    /** Returns the user id, or null when the token is malformed, forged or expired. */
    public static function decode(string $token): ?int
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return null;
        }
        [$header, $payload, $signature] = $parts;
        $expected = self::b64(hash_hmac('sha256', "$header.$payload", self::secret(), true));
        if (! hash_equals($expected, $signature)) {
            return null;
        }
        $claims = json_decode(base64_decode(strtr($payload, '-_', '+/')), true);
        if (! is_array($claims) || ! isset($claims['sub'], $claims['exp']) || $claims['exp'] < time()) {
            return null;
        }

        return (int) $claims['sub'];
    }

    private static function secret(): string
    {
        return config('app.jwt_secret') ?: (string) config('app.key');
    }

    private static function b64(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }
}
