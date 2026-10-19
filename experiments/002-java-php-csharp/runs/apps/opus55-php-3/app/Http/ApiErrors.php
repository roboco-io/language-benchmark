<?php

namespace App\Http;

use App\Exceptions\ApiException;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Http\Request;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;
use Throwable;

/**
 * Renders every error raised under /api as the RealWorld error envelope.
 */
class ApiErrors
{
    public static function register(Exceptions $exceptions): void
    {
        $exceptions->dontReport(ApiException::class);

        $exceptions->render(function (ApiException $e) {
            return response()->json(['errors' => $e->errors], $e->status);
        });

        $exceptions->render(function (HttpExceptionInterface $e) {
            $status = $e->getStatusCode();
            $errors = $status === 404 ? ['resource' => ['not found']] : ['request' => [$e->getMessage() ?: 'error']];

            return response()->json(['errors' => $errors], $status, $e->getHeaders());
        });

        $exceptions->render(function (Throwable $e, Request $request) {
            if (config('app.debug')) {
                return null;
            }

            return response()->json(['errors' => ['server' => ['internal error']]], 500);
        });
    }
}
