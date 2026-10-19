<?php

namespace App\Http\Middleware;

use App\Exceptions\ApiException;
use App\Models\User;
use App\Support\Token;
use Closure;
use Illuminate\Http\Request;

/** JWT auth. With the `optional` parameter, anonymous requests pass through. */
class Authenticate
{
    public function handle(Request $request, Closure $next, ?string $mode = null)
    {
        $header = (string) $request->header('Authorization', '');
        $user = null;

        if (preg_match('/^(?:Token|Bearer)\s+(\S+)$/i', $header, $m)) {
            $id = Token::verify($m[1]);
            $user = $id ? User::find($id) : null;
        }

        if ($user === null && $mode !== 'optional') {
            throw ApiException::unauthorized();
        }

        $request->attributes->set('auth_user', $user);
        $request->attributes->set('auth_token', $user ? $m[1] : null);

        return $next($request);
    }
}
