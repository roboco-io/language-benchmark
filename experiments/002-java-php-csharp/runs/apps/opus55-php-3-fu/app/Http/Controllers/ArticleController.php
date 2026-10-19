<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Http\Presenter;
use App\Models\Article;
use App\Models\User;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Collection;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
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
            $query->whereIn('id', DB::table('article_tag')
                ->join('tags', 'tags.id', '=', 'article_tag.tag_id')
                ->where('tags.name', $tag)
                ->select('article_tag.article_id'));
        }

        if (is_string($author = $request->query('author')) && $author !== '') {
            $query->whereIn('author_id', User::where('username', $author)->select('id'));
        }

        if (is_string($favorited = $request->query('favorited')) && $favorited !== '') {
            $query->whereIn('id', DB::table('favorites')
                ->join('users', 'users.id', '=', 'favorites.user_id')
                ->where('users.username', $favorited)
                ->select('favorites.article_id'));
        }

        return $this->paginate($request, $query);
    }

    public function feed(Request $request): JsonResponse
    {
        $query = Article::query()->whereIn('author_id', DB::table('follows')
            ->where('follower_id', $this->viewer($request)->id)
            ->select('followee_id'));

        return $this->paginate($request, $query);
    }

    public function show(Request $request, string $slug): JsonResponse
    {
        return $this->respond($request, $this->findArticle($slug));
    }

    public function store(Request $request): JsonResponse
    {
        $data = $this->payload($request, 'article');
        $errors = [];
        $title = $this->requireString($data, 'title', $errors);
        $description = $this->requireString($data, 'description', $errors);
        $body = $this->requireString($data, 'body', $errors);
        $tags = array_key_exists('tagList', $data) ? $this->tagList($data['tagList'], $errors) : [];
        $this->failIfErrors($errors);

        $article = DB::transaction(function () use ($request, $title, $description, $body, $tags) {
            $article = new Article([
                'title' => $title,
                'description' => $description,
                'body' => $body,
                'author_id' => $this->viewer($request)->id,
            ]);
            $this->saveWithUniqueSlug($article);
            $this->syncTags($article, $tags);

            return $article;
        });

        return $this->respond($request, $article, 201);
    }

    public function update(Request $request, string $slug): JsonResponse
    {
        $article = $this->findOwnArticle($request, $slug);
        $data = $this->payload($request, 'article');
        $errors = [];
        $changes = [];

        foreach (['title', 'description', 'body'] as $field) {
            if (array_key_exists($field, $data)) {
                $changes[$field] = $this->requireString($data, $field, $errors);
            }
        }

        $tags = array_key_exists('tagList', $data) ? $this->tagList($data['tagList'], $errors) : null;
        $this->failIfErrors($errors);

        DB::transaction(function () use ($article, $changes, $tags) {
            $titleChanged = isset($changes['title']) && $changes['title'] !== $article->title;
            $article->fill($changes);
            // Every successful update moves updatedAt, even when only the tags change.
            $article->updated_at = $article->freshTimestamp();

            if ($titleChanged) {
                $this->saveWithUniqueSlug($article);
            } else {
                $article->save();
            }

            if ($tags !== null) {
                $this->syncTags($article, $tags);
            }
        });

        return $this->respond($request, $article);
    }

    public function destroy(Request $request, string $slug): \Illuminate\Http\Response
    {
        $this->findOwnArticle($request, $slug)->delete();

        return response()->noContent();
    }

    public function favorite(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        DB::table('favorites')->insertOrIgnore(['user_id' => $this->viewer($request)->id, 'article_id' => $article->id]);

        return $this->respond($request, $article);
    }

    public function unfavorite(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        DB::table('favorites')->where(['user_id' => $this->viewer($request)->id, 'article_id' => $article->id])->delete();

        return $this->respond($request, $article);
    }

    private function findArticle(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiException::notFound('article');
    }

    private function findOwnArticle(Request $request, string $slug): Article
    {
        $article = $this->findArticle($slug);

        if ($article->author_id !== $this->viewer($request)->id) {
            throw ApiException::forbidden('article');
        }

        return $article;
    }

    /**
     * Validates a tagList value: it must be an array of strings. Blank and duplicate tags are dropped.
     *
     * @param  array<string, list<string>>  $errors
     * @return list<string>
     */
    private function tagList(mixed $value, array &$errors): array
    {
        if (! is_array($value) || ! array_is_list($value)) {
            $errors['tagList'][] = 'must be an array of strings';

            return [];
        }

        $tags = [];
        foreach ($value as $tag) {
            if (! is_string($tag)) {
                $errors['tagList'][] = 'must be an array of strings';

                return [];
            }
            $tag = trim($tag);
            if ($tag !== '') {
                $tags[$tag] = true;
            }
        }

        return array_keys($tags);
    }

    /**
     * Saves the article with a slug derived from its title. A random suffix is added when
     * the plain slug is already used by another article.
     */
    private function saveWithUniqueSlug(Article $article): void
    {
        $base = Str::slug($article->title) ?: 'article';
        $slug = $base;

        for ($attempt = 0; ; $attempt++) {
            $taken = Article::where('slug', $slug)->when($article->exists, fn ($q) => $q->whereKeyNot($article->id))->exists();

            if (! $taken) {
                $article->slug = $slug;
                try {
                    $article->save();

                    return;
                } catch (UniqueConstraintViolationException $e) {
                    if ($attempt >= 5) {
                        throw $e;
                    }
                }
            }

            $slug = $base.'-'.Str::lower(Str::random(8));
        }
    }

    /**
     * Replaces the article's tags, keeping the given order.
     *
     * @param  list<string>  $names
     */
    private function syncTags(Article $article, array $names): void
    {
        DB::table('article_tag')->where('article_id', $article->id)->delete();

        if ($names === []) {
            return;
        }

        DB::table('tags')->insertOrIgnore(array_map(fn ($name) => ['name' => $name], $names));
        $ids = DB::table('tags')->whereIn('name', $names)->pluck('id', 'name');

        DB::table('article_tag')->insert(array_map(
            fn ($name) => ['article_id' => $article->id, 'tag_id' => $ids[$name]],
            $names,
        ));
    }

    private function paginate(Request $request, Builder $query): JsonResponse
    {
        $limit = filter_var($request->query('limit'), FILTER_VALIDATE_INT, ['options' => ['min_range' => 0]]);
        $offset = filter_var($request->query('offset'), FILTER_VALIDATE_INT, ['options' => ['min_range' => 0]]);
        $limit = $limit === false ? self::DEFAULT_LIMIT : min($limit, self::MAX_LIMIT);
        $offset = $offset === false ? 0 : $offset;

        $count = (clone $query)->count();

        // The column list must be set before withDetails() adds the aggregate subqueries.
        $query->select(['articles.id', 'slug', 'title', 'description', 'author_id', 'articles.created_at', 'articles.updated_at']);

        $articles = $limit === 0 ? new Collection : $this->withDetails($request, $query)
            ->orderByDesc('created_at')
            ->orderByDesc('id')
            ->offset($offset)
            ->limit($limit)
            ->get();

        $following = $this->followedAmong($request, $articles->pluck('author_id')->unique()->all());

        return response()->json([
            'articles' => $articles->map(
                fn (Article $article) => Presenter::article($article, isset($following[$article->author_id]), withBody: false)
            )->all(),
            'articlesCount' => $count,
        ]);
    }

    private function respond(Request $request, Article $article, int $status = 200): JsonResponse
    {
        $article = $this->withDetails($request, Article::query())->whereKey($article->id)->firstOrFail();
        $following = $this->followedAmong($request, [$article->author_id]);

        return response()->json(
            ['article' => Presenter::article($article, isset($following[$article->author_id]))],
            $status,
        );
    }

    /** Adds eager loads and the favorites aggregates for the current viewer. */
    private function withDetails(Request $request, Builder $query): Builder
    {
        $query->with(['author', 'tags'])->withCount('favoritedBy as favorites_count');

        if ($viewer = $this->viewer($request)) {
            $query->withExists(['favoritedBy as favorited' => fn ($q) => $q->where('users.id', $viewer->id)]);
        }

        return $query;
    }

    /**
     * Returns the subset of $authorIds that the viewer follows, as a set keyed by id.
     *
     * @param  list<int>  $authorIds
     * @return array<int, true>
     */
    private function followedAmong(Request $request, array $authorIds): array
    {
        $viewer = $this->viewer($request);

        if ($viewer === null || $authorIds === []) {
            return [];
        }

        return DB::table('follows')
            ->where('follower_id', $viewer->id)
            ->whereIn('followee_id', $authorIds)
            ->pluck('followee_id')
            ->mapWithKeys(fn ($id) => [(int) $id => true])
            ->all();
    }
}
