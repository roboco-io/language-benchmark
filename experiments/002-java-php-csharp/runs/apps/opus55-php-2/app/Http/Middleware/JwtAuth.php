<?php

namespace App\Http\Middleware;

use App\Models\User;
use App\Support\ApiException;
use App\Support\Jwt;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Resolves the user from an "Authorization: Token <jwt>" header (a "Bearer" prefix is also accepted).
 *
 * Usage: "jwt" requires a valid token; "jwt:optional" only resolves the user when a token is sent.
 * The resolved user is stored in the request attribute "user".
 */
class JwtAuth
{
    public function handle(Request $request, Closure $next, string $mode = 'required'): Response
    {
        $header = (string) $request->header('Authorization', '');

        if ($header === '') {
            if ($mode !== 'optional') {
                throw ApiException::make(401, 'token', 'is missing');
            }

            return $next($request);
        }

        if (! preg_match('/^(?:Token|Bearer)\s+(\S+)$/i', $header, $matches)) {
            throw ApiException::make(401, 'token', 'is invalid');
        }

        $userId = Jwt::userId($matches[1]);
        $user = $userId === null ? null : User::find($userId);

        if ($user === null) {
            throw ApiException::make(401, 'token', 'is invalid');
        }

        $request->attributes->set('user', $user);
        $request->attributes->set('token', $matches[1]);

        return $next($request);
    }
}
