<?php

namespace App\Http\Middleware;

use App\Models\User;
use App\Support\ApiError;
use App\Support\Jwt;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Resolves the user from an "Authorization: Token <jwt>" header.
 *
 * With the "optional" parameter, requests without a token pass through anonymously,
 * but a token that is present must still be valid.
 */
class AuthenticateToken
{
    public function handle(Request $request, Closure $next, ?string $mode = null): Response
    {
        $header = (string) $request->header('Authorization', '');
        $token = null;
        if (preg_match('/^(?:Token|Bearer)\s+(\S+)$/i', trim($header), $matches)) {
            $token = $matches[1];
        }

        if ($token === null) {
            if ($mode === 'optional') {
                return $next($request);
            }
            throw ApiError::make(401, 'token', 'is missing');
        }

        $userId = Jwt::verify($token);
        $user = $userId === null ? null : User::find($userId);
        if ($user === null) {
            throw ApiError::make(401, 'token', 'is invalid');
        }

        $request->attributes->set('user', $user);

        return $next($request);
    }
}
