<?php

namespace App\Support;

use App\Models\Article;
use App\Models\Comment;
use App\Models\User;
use DateTimeInterface;
use Illuminate\Support\Facades\DB;

/**
 * Converts models into RealWorld response shapes.
 */
class Present
{
    public static function user(User $user): array
    {
        return [
            'email' => $user->email,
            'token' => Jwt::issue($user->id),
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
     * Returns the subset of $userIds that $viewer follows, as a lookup set.
     *
     * @param  list<int>  $userIds
     * @return array<int, true>
     */
    public static function followedIds(?User $viewer, array $userIds): array
    {
        if ($viewer === null || $userIds === []) {
            return [];
        }

        return DB::table('follows')
            ->where('follower_id', $viewer->id)
            ->whereIn('followee_id', array_unique($userIds))
            ->pluck('followee_id')
            ->mapWithKeys(fn ($id) => [(int) $id => true])
            ->all();
    }

    /**
     * Expects the article to be loaded through ArticleQuery (author, tags, counts).
     */
    public static function article(Article $article, array $followed, bool $withBody = true): array
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
            'createdAt' => self::date($article->created_at),
            'updatedAt' => self::date($article->updated_at),
            'favorited' => (bool) ($article->favorited ?? false),
            'favoritesCount' => (int) $article->favorites_count,
            'author' => self::profile($article->author, isset($followed[$article->author_id])),
        ];
    }

    public static function comment(Comment $comment, array $followed): array
    {
        return [
            'id' => $comment->id,
            'createdAt' => self::date($comment->created_at),
            'updatedAt' => self::date($comment->updated_at),
            'body' => $comment->body,
            'author' => self::profile($comment->author, isset($followed[$comment->author_id])),
        ];
    }

    public static function date(DateTimeInterface $date): string
    {
        return $date->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d\TH:i:s.v\Z');
    }
}
