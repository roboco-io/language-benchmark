<?php

use App\Http\Middleware\JwtAuth;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Http\Request;
use Illuminate\Validation\ValidationException;
use Symfony\Component\HttpKernel\Exception\HttpExceptionInterface;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

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
        $exceptions->shouldRenderJsonWhen(fn (Request $request) => true);

        $exceptions->render(function (ValidationException $e) {
            return response()->json(['errors' => $e->errors()], 422);
        });
        $exceptions->render(function (NotFoundHttpException $e) {
            return response()->json(['errors' => ['resource' => ['not found']]], 404);
        });
        $exceptions->render(function (HttpExceptionInterface $e) {
            return response()->json(['errors' => ['request' => [$e->getMessage() ?: 'error']]], $e->getStatusCode());
        });
    })->create();
