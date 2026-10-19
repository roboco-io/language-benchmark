<?php

use App\Http\Middleware\JwtAuth;
use App\Support\ApiException;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware): void {
        $middleware->alias(['jwt' => JwtAuth::class]);
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        // Expected client errors (4xx with a RealWorld error body) are not logged.
        $exceptions->dontReport(ApiException::class);
        $exceptions->shouldRenderJsonWhen(fn (Request $request) => true);
    })->create();
