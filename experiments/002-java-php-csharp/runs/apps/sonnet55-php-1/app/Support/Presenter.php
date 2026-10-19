<?php

namespace App\Support;

use App\Models\Article;
use App\Models\Comment;
use App\Models\User;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;

/** Builds the RealWorld JSON shapes. Viewer-specific flags are resolved with batched queries. */
class Presenter
{
    public static function iso($date): string
    {
        return $date->copy()->utc()->format('Y-m-d\TH:i:s.v\Z');
    }

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

    /** @param Collection<int, User> $users */
    public static function followingSet(?User $viewer, Collection $users): array
    {
        if (! $viewer || $users->isEmpty()) {
            return [];
        }

        return DB::table('follows')->where('follower_id', $viewer->id)
            ->whereIn('followed_id', $users->pluck('id')->unique())
            ->pluck('followed_id')->flip()->all();
    }

    /**
     * @param  Collection<int, Article>  $articles  with author, tags and favorited_by_count loaded
     */
    public static function articles(Collection $articles, ?User $viewer, bool $withBody = true): array
    {
        $following = self::followingSet($viewer, $articles->pluck('author'));
        $favorited = $viewer && $articles->isNotEmpty()
            ? DB::table('favorites')->where('user_id', $viewer->id)
                ->whereIn('article_id', $articles->pluck('id'))->pluck('article_id')->flip()->all()
            : [];

        return $articles->map(fn (Article $a) => [
            'slug' => $a->slug,
            'title' => $a->title,
            'description' => $a->description,
        ] + ($withBody ? ['body' => $a->body] : []) + [
            'tagList' => $a->tags->pluck('name')->sort()->values()->all(),
            'createdAt' => self::iso($a->created_at),
            'updatedAt' => self::iso($a->updated_at),
            'favorited' => isset($favorited[$a->id]),
            'favoritesCount' => (int) $a->favorited_by_count,
            'author' => self::profile($a->author, isset($following[$a->user_id])),
        ])->all();
    }

    public static function article(Article $article, ?User $viewer): array
    {
        $article->loadMissing(['author', 'tags'])->loadCount('favoritedBy');

        return self::articles(collect([$article]), $viewer)[0];
    }

    public static function comments(Collection $comments, ?User $viewer): array
    {
        $following = self::followingSet($viewer, $comments->pluck('author'));

        return $comments->map(fn (Comment $c) => [
            'id' => $c->id,
            'createdAt' => self::iso($c->created_at),
            'updatedAt' => self::iso($c->updated_at),
            'body' => $c->body,
            'author' => self::profile($c->author, isset($following[$c->user_id])),
        ])->all();
    }
}
