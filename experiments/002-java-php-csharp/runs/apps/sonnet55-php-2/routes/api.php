<?php

use App\Http\Controllers\ArticleController;
use App\Http\Controllers\CommentController;
use App\Http\Controllers\ProfileController;
use App\Http\Controllers\UserController;
use Illuminate\Support\Facades\Route;

Route::post('users', [UserController::class, 'register']);
Route::post('users/login', [UserController::class, 'login']);
Route::get('tags', [ArticleController::class, 'tags']);

Route::middleware('rw.auth:optional')->group(function () {
    Route::get('profiles/{username}', [ProfileController::class, 'show']);
    Route::get('articles', [ArticleController::class, 'index']);
    Route::get('articles/{slug}', [ArticleController::class, 'show'])->where('slug', '(?!feed$)[^/]+');
    Route::get('articles/{slug}/comments', [CommentController::class, 'index']);
});

Route::middleware('rw.auth')->group(function () {
    Route::get('user', [UserController::class, 'show']);
    Route::put('user', [UserController::class, 'update']);
    Route::post('profiles/{username}/follow', [ProfileController::class, 'follow']);
    Route::delete('profiles/{username}/follow', [ProfileController::class, 'unfollow']);
    Route::get('articles/feed', [ArticleController::class, 'feed']);
    Route::post('articles', [ArticleController::class, 'store']);
    Route::put('articles/{slug}', [ArticleController::class, 'update']);
    Route::delete('articles/{slug}', [ArticleController::class, 'destroy']);
    Route::post('articles/{slug}/favorite', [ArticleController::class, 'favorite']);
    Route::delete('articles/{slug}/favorite', [ArticleController::class, 'unfavorite']);
    Route::post('articles/{slug}/comments', [CommentController::class, 'store']);
    Route::delete('articles/{slug}/comments/{id}', [CommentController::class, 'destroy'])->whereNumber('id');
});
