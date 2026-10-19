<?php

use App\Exceptions\ApiException;
use App\Http\Middleware\Authenticate;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware): void {
        $middleware->alias(['jwt' => Authenticate::class]);
    })
    ->withExceptions(function (Exceptions $exceptions): void {
        $exceptions->shouldRenderJsonWhen(fn (Request $request) => true);
        $exceptions->dontReport(ApiException::class);
        $exceptions->render(fn (ApiException $e) => response()->json(['errors' => $e->errors], $e->status));
        $exceptions->render(function (HttpExceptionInterface $e) {
            $what = $e->getStatusCode() === 404 ? 'resource' : 'request';

            return response()->json(['errors' => [$what => [$e->getStatusCode() === 404 ? 'not found' : 'invalid']]], $e->getStatusCode());
        });
    })->create();
