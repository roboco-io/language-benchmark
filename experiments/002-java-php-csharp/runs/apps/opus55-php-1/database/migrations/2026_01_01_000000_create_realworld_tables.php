<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('users', function (Blueprint $table) {
            $table->id();
            $table->string('username')->unique();
            $table->string('email')->unique();
            $table->string('password');
            $table->text('bio')->nullable();
            $table->string('image')->nullable();
            $table->timestamps(3);
        });

        Schema::create('follows', function (Blueprint $table) {
            $table->foreignId('follower_id')->constrained('users')->cascadeOnDelete();
            $table->foreignId('followee_id')->constrained('users')->cascadeOnDelete();
            $table->primary(['follower_id', 'followee_id']);
            $table->index('followee_id');
        });

        Schema::create('articles', function (Blueprint $table) {
            $table->id();
            $table->string('slug')->unique();
            $table->string('title');
            $table->text('description');
            $table->text('body');
            $table->foreignId('author_id')->constrained('users')->cascadeOnDelete();
            $table->timestamps(3);
            $table->index('author_id');
        });

        Schema::create('tags', function (Blueprint $table) {
            $table->id();
            $table->string('name')->unique();
        });

        // The auto-increment id preserves the order in which tags were given.
        Schema::create('article_tag', function (Blueprint $table) {
            $table->id();
            $table->foreignId('article_id')->constrained()->cascadeOnDelete();
            $table->foreignId('tag_id')->constrained()->cascadeOnDelete();
            $table->unique(['article_id', 'tag_id']);
            $table->index('tag_id');
        });

        Schema::create('favorites', function (Blueprint $table) {
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('article_id')->constrained()->cascadeOnDelete();
            $table->primary(['user_id', 'article_id']);
            $table->index('article_id');
        });

        Schema::create('comments', function (Blueprint $table) {
            $table->id();
            $table->text('body');
            $table->foreignId('article_id')->constrained()->cascadeOnDelete();
            $table->foreignId('author_id')->constrained('users')->cascadeOnDelete();
            $table->timestamps(3);
            $table->index('article_id');
        });
    }

    public function down(): void
    {
        foreach (['comments', 'favorites', 'article_tag', 'tags', 'articles', 'follows', 'users'] as $table) {
            Schema::dropIfExists($table);
        }
    }
};
