<?php

namespace App\Support;

use App\Models\Article;
use App\Models\Comment;
use App\Models\User;
use Illuminate\Database\Eloquent\Collection as EloquentCollection;
use Illuminate\Support\Facades\DB;

/** Builds the RealWorld JSON shapes for users, profiles, articles and comments. */
class Serializer
{
    public static function time($value): string
    {
        return $value->clone()->utc()->format('Y-m-d\TH:i:s.v\Z');
    }

    public static function user(User $user): array
    {
        return [
            'email' => $user->email,
            'token' => Jwt::encode($user->id),
            'username' => $user->username,
            'bio' => $user->bio,
            'image' => $user->image,
        ];
    }

    public static function profile(User $user, ?User $viewer, ?bool $following = null): array
    {
        $following ??= $viewer
            ? DB::table('follows')->where('follower_id', $viewer->id)->where('followee_id', $user->id)->exists()
            : false;

        return [
            'username' => $user->username,
            'bio' => $user->bio,
            'image' => $user->image,
            'following' => $following,
        ];
    }

    /**
     * Serializes articles with a constant number of queries (tags, authors, favorites, follows are batched).
     *
     * @param  iterable<Article>  $articles
     */
    public static function articles(iterable $articles, ?User $viewer, bool $withBody = true): array
    {
        $articles = new EloquentCollection(is_array($articles) ? $articles : iterator_to_array($articles, false));
        if ($articles->isEmpty()) {
            return [];
        }
        $articles->load(['author', 'tags']);
        $ids = $articles->pluck('id');
        $counts = DB::table('favorites')->whereIn('article_id', $ids)
            ->selectRaw('article_id, count(*) as c')->groupBy('article_id')->pluck('c', 'article_id');
        $favorited = $viewer
            ? DB::table('favorites')->where('user_id', $viewer->id)->whereIn('article_id', $ids)->pluck('article_id')->flip()
            : collect();
        $followed = $viewer
            ? DB::table('follows')->where('follower_id', $viewer->id)
                ->whereIn('followee_id', $articles->pluck('user_id'))->pluck('followee_id')->flip()
            : collect();

        return $articles->map(fn (Article $a) => array_filter([
            'slug' => $a->slug,
            'title' => $a->title,
            'description' => $a->description,
            'body' => $withBody ? $a->body : null,
            'tagList' => $a->tags->pluck('name')->all(),
            'createdAt' => self::time($a->created_at),
            'updatedAt' => self::time($a->updated_at),
            'favorited' => $favorited->has($a->id),
            'favoritesCount' => (int) ($counts[$a->id] ?? 0),
            'author' => self::profile($a->author, $viewer, $followed->has($a->user_id)),
        ], fn ($v, $k) => $k !== 'body' || $v !== null, ARRAY_FILTER_USE_BOTH))->all();
    }

    public static function article(Article $article, ?User $viewer): array
    {
        return self::articles([$article], $viewer)[0];
    }

    public static function comment(Comment $comment, ?User $viewer): array
    {
        return [
            'id' => $comment->id,
            'createdAt' => self::time($comment->created_at),
            'updatedAt' => self::time($comment->updated_at),
            'body' => $comment->body,
            'author' => self::profile($comment->author, $viewer),
        ];
    }
}
