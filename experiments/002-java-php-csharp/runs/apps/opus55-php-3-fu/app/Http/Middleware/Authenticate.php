<?php

namespace App\Http\Middleware;

use App\Exceptions\ApiException;
use App\Models\User;
use App\Support\Jwt;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Resolves the user from an "Authorization: Token <jwt>" header (a "Bearer" prefix is also
 * accepted) and stores it in the "user" request attribute. In "required" mode a missing
 * token is rejected; in "optional" mode the request continues anonymously.
 */
class Authenticate
{
    public function handle(Request $request, Closure $next, string $mode = 'required'): Response
    {
        $header = (string) $request->header('Authorization', '');
        $token = null;

        if (preg_match('/^(?:Token|Bearer)\s+(\S+)\s*$/i', $header, $matches)) {
            $token = $matches[1];
        }

        if ($token === null) {
            if ($header !== '' || $mode === 'required') {
                throw ApiException::unauthorized($header === '' ? 'is missing' : 'is invalid');
            }

            return $next($request);
        }

        $userId = Jwt::verify($token);
        $user = $userId === null ? null : User::find($userId);

        if ($user === null) {
            throw ApiException::unauthorized('is invalid');
        }

        $request->attributes->set('user', $user);

        return $next($request);
    }
}
