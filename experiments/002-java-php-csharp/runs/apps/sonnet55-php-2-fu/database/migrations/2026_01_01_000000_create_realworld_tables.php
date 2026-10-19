<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('users', function (Blueprint $t) {
            $t->id();
            $t->string('username')->unique();
            $t->string('email')->unique();
            $t->string('password');
            $t->text('bio')->nullable();
            $t->string('image')->nullable();
            $t->timestamps();
        });

        Schema::create('articles', function (Blueprint $t) {
            $t->id();
            $t->foreignId('user_id')->constrained()->cascadeOnDelete();
            $t->string('slug')->unique();
            $t->string('title');
            $t->string('description');
            $t->text('body');
            $t->timestamps();
            $t->index(['user_id', 'id']);
        });

        Schema::create('tags', function (Blueprint $t) {
            $t->id();
            $t->string('name')->unique();
        });

        Schema::create('article_tag', function (Blueprint $t) {
            $t->foreignId('article_id')->constrained()->cascadeOnDelete();
            $t->foreignId('tag_id')->constrained()->cascadeOnDelete();
            $t->primary(['article_id', 'tag_id']);
            $t->index('tag_id');
        });

        Schema::create('favorites', function (Blueprint $t) {
            $t->foreignId('user_id')->constrained()->cascadeOnDelete();
            $t->foreignId('article_id')->constrained()->cascadeOnDelete();
            $t->primary(['user_id', 'article_id']);
            $t->index('article_id');
        });

        Schema::create('follows', function (Blueprint $t) {
            $t->foreignId('follower_id')->constrained('users')->cascadeOnDelete();
            $t->foreignId('followee_id')->constrained('users')->cascadeOnDelete();
            $t->primary(['follower_id', 'followee_id']);
            $t->index('followee_id');
        });

        Schema::create('comments', function (Blueprint $t) {
            $t->id();
            $t->foreignId('article_id')->constrained()->cascadeOnDelete();
            $t->foreignId('user_id')->constrained()->cascadeOnDelete();
            $t->text('body');
            $t->timestamps();
            $t->index('article_id');
        });
    }

    public function down(): void
    {
        foreach (['comments', 'follows', 'favorites', 'article_tag', 'tags', 'articles', 'users'] as $table) {
            Schema::dropIfExists($table);
        }
    }
};
