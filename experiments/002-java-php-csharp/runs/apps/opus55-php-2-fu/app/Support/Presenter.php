<?php

namespace App\Support;

use App\Models\Article;
use App\Models\Comment;
use App\Models\User;
use DateTimeInterface;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;

/**
 * Converts models to RealWorld JSON shapes.
 */
class Presenter
{
    public static function user(User $user, string $token): array
    {
        return ['user' => [
            'email' => $user->email,
            'token' => $token,
            'username' => $user->username,
            'bio' => $user->bio,
            'image' => $user->image,
        ]];
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

    public static function isFollowing(?User $viewer, User $target): bool
    {
        return $viewer !== null && DB::table('follows')
            ->where('follower_id', $viewer->id)
            ->where('followee_id', $target->id)
            ->exists();
    }

    /** Adds the eager loads needed to present articles for the given viewer. */
    public static function withArticleData(Builder $query, ?User $viewer): Builder
    {
        $query->with(['author', 'tags'])->withCount('favoritedBy as favorites_count');

        if ($viewer !== null) {
            $query->withExists(['favoritedBy as favorited' => fn ($q) => $q->where('users.id', $viewer->id)]);
        }

        return $query;
    }

    /** Reloads one article with everything needed to present it. */
    public static function article(Article $article, ?User $viewer): array
    {
        $article = self::withArticleData(Article::whereKey($article->id), $viewer)->firstOrFail();

        return ['article' => self::articleData($article, $viewer, self::followedIds($viewer, collect([$article])), true)];
    }

    /** @param Collection<int, Article> $articles */
    public static function articles(Collection $articles, ?User $viewer, int $count): array
    {
        $followed = self::followedIds($viewer, $articles);

        return [
            'articles' => $articles->map(fn (Article $a) => self::articleData($a, $viewer, $followed, false))->all(),
            'articlesCount' => $count,
        ];
    }

    public static function comment(Comment $comment, bool $following): array
    {
        return [
            'id' => $comment->id,
            'createdAt' => self::date($comment->created_at),
            'updatedAt' => self::date($comment->updated_at),
            'body' => $comment->body,
            'author' => self::profile($comment->author, $following),
        ];
    }

    public static function date(?DateTimeInterface $date): ?string
    {
        return $date?->format('Y-m-d\TH:i:s.v\Z');
    }

    /**
     * @param Collection<int, Article> $articles
     * @return array<int, true> Ids of the authors (of the given articles) that the viewer follows.
     */
    private static function followedIds(?User $viewer, Collection $articles): array
    {
        if ($viewer === null || $articles->isEmpty()) {
            return [];
        }

        return DB::table('follows')
            ->where('follower_id', $viewer->id)
            ->whereIn('followee_id', $articles->pluck('author_id')->unique()->values())
            ->pluck('followee_id')
            ->mapWithKeys(fn ($id) => [(int) $id => true])
            ->all();
    }

    /** @param array<int, true> $followed */
    private static function articleData(Article $article, ?User $viewer, array $followed, bool $withBody): array
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
            'favorited' => $viewer !== null && (bool) $article->favorited,
            'favoritesCount' => (int) $article->favorites_count,
            'author' => self::profile($article->author, isset($followed[$article->author_id])),
        ];
    }
}
