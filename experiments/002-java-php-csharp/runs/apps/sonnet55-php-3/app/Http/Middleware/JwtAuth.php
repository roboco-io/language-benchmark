<?php

namespace App\Http\Middleware;

use App\Models\User;
use App\Support\JwtToken;
use Closure;
use Illuminate\Http\Request;

class JwtAuth
{
    /** Pass "optional" to allow anonymous access while still resolving a user when a token is sent. */
    public function handle(Request $request, Closure $next, ?string $mode = null)
    {
        $header = (string) $request->header('Authorization', '');
        $token = null;
        if (preg_match('/^(?:Token|Bearer)\s+(.+)$/i', $header, $m)) {
            $token = trim($m[1]);
        }

        $user = null;
        if ($token !== null) {
            $id = JwtToken::verify($token);
            $user = $id ? User::find($id) : null;
        }

        if ($user === null && $mode !== 'optional') {
            $msg = $token === null ? 'is missing' : 'is invalid';

            return response()->json(['errors' => ['token' => [$msg]]], 401);
        }

        $request->attributes->set('auth_user', $user);
        $request->attributes->set('auth_token', $token);

        return $next($request);
    }
}
