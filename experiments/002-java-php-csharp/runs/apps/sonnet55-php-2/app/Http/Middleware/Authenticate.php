<?php

namespace App\Http\Middleware;

use App\Models\User;
use App\Support\ApiException;
use App\Support\Jwt;
use Closure;
use Illuminate\Http\Request;

/** Resolves the JWT user into the `auth_user` request attribute; `required` rejects anonymous calls. */
class Authenticate
{
    public function handle(Request $request, Closure $next, string $mode = 'required')
    {
        $user = null;
        $header = (string) $request->header('Authorization', '');
        if (preg_match('/^(?:Token|Bearer)\s+(\S+)$/i', $header, $m)) {
            $id = Jwt::decode($m[1]);
            $user = $id ? User::find($id) : null;
        }

        if ($mode === 'required' && ! $user) {
            throw ApiException::field(401, 'token', $header === '' ? 'is missing' : 'is invalid');
        }

        $request->attributes->set('auth_user', $user);

        return $next($request);
    }
}
