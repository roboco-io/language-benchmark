<?php

namespace App\Support;

/**
 * Minimal HS256 JSON Web Token implementation.
 */
class Jwt
{
    private const TTL_SECONDS = 60 * 60 * 24 * 30;

    public static function issue(int $userId): string
    {
        $now = time();
        $header = self::base64UrlEncode(json_encode(['alg' => 'HS256', 'typ' => 'JWT']));
        $payload = self::base64UrlEncode(json_encode(['sub' => (string) $userId, 'iat' => $now, 'exp' => $now + self::TTL_SECONDS]));

        return "$header.$payload.".self::sign("$header.$payload");
    }

    /**
     * Returns the user id stored in a valid token, or null when the token is invalid or expired.
     */
    public static function verify(string $token): ?int
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return null;
        }
        [$header, $payload, $signature] = $parts;
        if (! hash_equals(self::sign("$header.$payload"), $signature)) {
            return null;
        }
        $headerData = json_decode(self::base64UrlDecode($header), true);
        if (! is_array($headerData) || ($headerData['alg'] ?? null) !== 'HS256') {
            return null;
        }
        $claims = json_decode(self::base64UrlDecode($payload), true);
        if (! is_array($claims) || ! isset($claims['sub'], $claims['exp']) || $claims['exp'] < time()) {
            return null;
        }

        return ctype_digit((string) $claims['sub']) ? (int) $claims['sub'] : null;
    }

    private static function sign(string $input): string
    {
        return self::base64UrlEncode(hash_hmac('sha256', $input, self::secret(), true));
    }

    private static function secret(): string
    {
        return (string) (config('app.jwt_secret') ?: config('app.key'));
    }

    private static function base64UrlEncode(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }

    private static function base64UrlDecode(string $data): string
    {
        return (string) base64_decode(strtr($data, '-_', '+/'), true);
    }
}
