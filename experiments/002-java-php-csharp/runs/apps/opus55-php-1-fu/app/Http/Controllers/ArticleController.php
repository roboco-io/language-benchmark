<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Tag;
use App\Models\User;
use App\Support\ApiError;
use App\Support\Present;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Collection;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class ArticleController extends Controller
{
    private const DEFAULT_LIMIT = 20;

    private const MAX_LIMIT = 100;

    public function index(Request $request): JsonResponse
    {
        $query = Article::query();

        if (is_string($tag = $request->query('tag')) && $tag !== '') {
            $query->whereHas('tags', fn (Builder $q) => $q->where('name', $tag));
        }
        if (is_string($author = $request->query('author')) && $author !== '') {
            $query->whereHas('author', fn (Builder $q) => $q->where('username', $author));
        }
        if (is_string($favorited = $request->query('favorited')) && $favorited !== '') {
            $query->whereHas('favoritedBy', fn (Builder $q) => $q->where('username', $favorited));
        }

        return $this->listResponse($request, $query);
    }

    public function feed(Request $request): JsonResponse
    {
        $viewer = $this->currentUser($request);
        $query = Article::query()->whereIn(
            'author_id',
            DB::table('follows')->select('followee_id')->where('follower_id', $viewer->id),
        );

        return $this->listResponse($request, $query);
    }

    public function show(Request $request, string $slug): JsonResponse
    {
        return $this->articleResponse($request, $this->findArticle($slug));
    }

    public function store(Request $request): JsonResponse
    {
        $viewer = $this->currentUser($request);
        $data = $this->payload($request, 'article');
        $errors = $this->blankErrors($data, ['title', 'description', 'body']);
        if (array_key_exists('tagList', $data) && ! $this->isTagList($data['tagList'])) {
            $errors['tagList'] = ['must be an array of strings'];
        }
        if ($errors !== []) {
            throw ApiError::validation($errors);
        }

        $article = DB::transaction(function () use ($data, $viewer) {
            $article = $this->createWithUniqueSlug(fn (string $slug) => Article::create([
                'slug' => $slug,
                'title' => $data['title'],
                'description' => $data['description'],
                'body' => $data['body'],
                'author_id' => $viewer->id,
            ]), $data['title']);
            $this->syncTags($article, $data['tagList'] ?? []);

            return $article;
        });

        return $this->articleResponse($request, $article, 201);
    }

    public function update(Request $request, string $slug): JsonResponse
    {
        $viewer = $this->currentUser($request);
        $article = $this->findArticle($slug);
        if ($article->author_id !== $viewer->id) {
            throw ApiError::forbidden('article');
        }

        $data = $this->payload($request, 'article');
        $present = array_values(array_intersect(['title', 'description', 'body'], array_keys($data)));
        $errors = $this->blankErrors($data, $present);
        if (array_key_exists('tagList', $data) && ! $this->isTagList($data['tagList'])) {
            $errors['tagList'] = ['must be an array of strings'];
        }
        if ($errors !== []) {
            throw ApiError::validation($errors);
        }

        DB::transaction(function () use ($article, $data) {
            $article->fill(array_intersect_key($data, array_flip(['title', 'description', 'body'])));
            if ($article->isDirty('title')) {
                $this->createWithUniqueSlug(function (string $slug) use ($article) {
                    $article->slug = $slug;
                    $article->save();
                }, $article->title);
            }
            // Always bump updated_at, even when only the tags change.
            $article->updated_at = $article->freshTimestamp();
            $article->save();
            if (array_key_exists('tagList', $data)) {
                $this->syncTags($article, $data['tagList']);
            }
        });

        return $this->articleResponse($request, $article);
    }

    public function destroy(Request $request, string $slug): Response
    {
        $article = $this->findArticle($slug);
        if ($article->author_id !== $this->currentUser($request)->id) {
            throw ApiError::forbidden('article');
        }
        $article->delete();

        return response()->noContent();
    }

    public function favorite(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        DB::table('favorites')->insertOrIgnore([
            'user_id' => $this->currentUser($request)->id,
            'article_id' => $article->id,
        ]);

        return $this->articleResponse($request, $article);
    }

    public function unfavorite(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        DB::table('favorites')->where([
            'user_id' => $this->currentUser($request)->id,
            'article_id' => $article->id,
        ])->delete();

        return $this->articleResponse($request, $article);
    }

    private function findArticle(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiError::notFound('article');
    }

    private function listResponse(Request $request, Builder $query): JsonResponse
    {
        $limit = $this->intQuery($request, 'limit', self::DEFAULT_LIMIT, 0, self::MAX_LIMIT);
        $offset = $this->intQuery($request, 'offset', 0, 0, PHP_INT_MAX);
        $viewer = $this->currentUser($request);

        $count = (clone $query)->count();
        $articles = $this->withDetails($query, $viewer)
            ->orderByDesc('articles.id')
            ->limit($limit)
            ->offset($offset)
            ->get();
        $followed = Present::followedIds($viewer, $articles->pluck('author_id')->all());

        return response()->json([
            'articles' => $articles->map(fn (Article $a) => Present::article($a, $followed, false))->all(),
            'articlesCount' => $count,
        ]);
    }

    private function articleResponse(Request $request, Article $article, int $status = 200): JsonResponse
    {
        $viewer = $this->currentUser($request);
        $article = $this->withDetails(Article::whereKey($article->id), $viewer)->firstOrFail();
        $followed = Present::followedIds($viewer, [$article->author_id]);

        return response()->json(['article' => Present::article($article, $followed)], $status);
    }

    private function withDetails(Builder $query, ?User $viewer): Builder
    {
        $query->with(['author', 'tags'])->withCount('favoritedBy as favorites_count');
        if ($viewer !== null) {
            $query->withExists(['favoritedBy as favorited' => fn (Builder $q) => $q->where('users.id', $viewer->id)]);
        }

        return $query;
    }

    private function intQuery(Request $request, string $key, int $default, int $min, int $max): int
    {
        $value = $request->query($key);
        if (! is_string($value) || ! preg_match('/^\d+$/', $value)) {
            return $default;
        }

        return max($min, min($max, (int) $value));
    }

    private function isTagList(mixed $value): bool
    {
        if (! is_array($value) || ! array_is_list($value)) {
            return false;
        }
        foreach ($value as $tag) {
            if (! is_string($tag)) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param  list<string>  $names
     */
    private function syncTags(Article $article, array $names): void
    {
        $names = array_values(array_unique(array_filter(array_map('trim', $names), fn ($n) => $n !== '')));
        DB::table('article_tag')->where('article_id', $article->id)->delete();
        if ($names === []) {
            return;
        }

        Tag::insertOrIgnore(array_map(fn ($name) => ['name' => $name], $names));
        $ids = Tag::whereIn('name', $names)->pluck('id', 'name');
        DB::table('article_tag')->insert(array_map(
            fn ($name) => ['article_id' => $article->id, 'tag_id' => $ids[$name]],
            $names,
        ));
    }

    /**
     * Calls $write with a slug derived from $title, retrying with a random suffix on collision.
     */
    private function createWithUniqueSlug(callable $write, string $title): mixed
    {
        $base = Str::slug($title) ?: 'article';
        $slug = $base;
        for ($attempt = 0; ; $attempt++) {
            if (! Article::where('slug', $slug)->exists()) {
                try {
                    return DB::transaction(fn () => $write($slug));
                } catch (UniqueConstraintViolationException $e) {
                    if ($attempt >= 5) {
                        throw $e;
                    }
                }
            }
            $slug = $base.'-'.Str::lower(Str::random(6));
        }
    }
}
