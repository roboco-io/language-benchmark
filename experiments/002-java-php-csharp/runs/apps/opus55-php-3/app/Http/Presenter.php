<?php

namespace App\Http;

use App\Models\Article;
use App\Models\Comment;
use App\Models\Model;
use App\Models\User;

/**
 * Converts models into the JSON shapes defined by the RealWorld API spec.
 */
class Presenter
{
    public static function user(User $user, string $token): array
    {
        return [
            'email' => $user->email,
            'token' => $token,
            'username' => $user->username,
            'bio' => $user->bio,
            'image' => $user->image,
        ];
    }

    public static function profile(User $user, bool $following): array
    {
        return [
            'username' => $user->username,
            'bio' => $user->bio,
            'image' => $user->image,
            'following' => $following,
        ];
    }

    /**
     * Expects the article to have "author" and "tags" loaded and the "favorites_count"
     * and (optionally) "favorited" attributes selected.
     */
    public static function article(Article $article, bool $following, bool $withBody = true): array
    {
        $data = [
            'slug' => $article->slug,
            'title' => $article->title,
            'description' => $article->description,
        ];

        if ($withBody) {
            $data['body'] = $article->body;
        }

        return $data + [
            'tagList' => $article->tags->pluck('name')->all(),
            'createdAt' => Model::isoDate($article->created_at),
            'updatedAt' => Model::isoDate($article->updated_at),
            'favorited' => (bool) ($article->favorited ?? false),
            'favoritesCount' => (int) ($article->favorites_count ?? 0),
            'author' => self::profile($article->author, $following),
        ];
    }

    public static function comment(Comment $comment, bool $following): array
    {
        return [
            'id' => (int) $comment->id,
            'createdAt' => Model::isoDate($comment->created_at),
            'updatedAt' => Model::isoDate($comment->updated_at),
            'body' => $comment->body,
            'author' => self::profile($comment->author, $following),
        ];
    }
}
