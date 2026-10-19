<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Tag;
use App\Models\User;
use App\Support\ApiException;
use App\Support\Input;
use App\Support\Presenter;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class ArticleController
{
    private const DEFAULT_LIMIT = 20;

    private const MAX_LIMIT = 100;

    public function index(Request $request): JsonResponse
    {
        $query = Article::query();

        if (($tag = $request->query('tag')) !== null) {
            $query->whereHas('tags', fn (Builder $q) => $q->where('name', $tag));
        }
        if (($author = $request->query('author')) !== null) {
            $query->whereHas('author', fn (Builder $q) => $q->where('username', $author));
        }
        if (($favorited = $request->query('favorited')) !== null) {
            $query->whereHas('favoritedBy', fn (Builder $q) => $q->where('username', $favorited));
        }

        return $this->paginate($request, $query);
    }

    public function feed(Request $request): JsonResponse
    {
        $viewer = $request->attributes->get('user');
        $query = Article::whereIn(
            'author_id',
            DB::table('follows')->select('followee_id')->where('follower_id', $viewer->id),
        );

        return $this->paginate($request, $query);
    }

    public function show(Request $request, string $slug): JsonResponse
    {
        return new JsonResponse(Presenter::article($this->find($slug), $request->attributes->get('user')));
    }

    public function store(Request $request): JsonResponse
    {
        /** @var User $viewer */
        $viewer = $request->attributes->get('user');
        $input = Input::from($request, 'article');
        $title = $input->required('title');
        $description = $input->required('description');
        $body = $input->required('body');
        $tags = $input->stringList('tagList') ?? [];
        $input->validate();

        $article = DB::transaction(function () use ($viewer, $title, $description, $body, $tags) {
            $article = Article::create([
                'slug' => $this->uniqueSlug($title),
                'title' => $title,
                'description' => $description,
                'body' => $body,
                'author_id' => $viewer->id,
            ]);
            $this->syncTags($article, $tags);

            return $article;
        });

        return new JsonResponse(Presenter::article($article, $viewer), 201);
    }

    public function update(Request $request, string $slug): JsonResponse
    {
        /** @var User $viewer */
        $viewer = $request->attributes->get('user');
        $article = $this->find($slug);
        if ($article->author_id !== $viewer->id) {
            throw ApiException::forbidden('article');
        }

        $input = Input::from($request, 'article');
        $changes = [];
        foreach (['title', 'description', 'body'] as $field) {
            if ($input->has($field)) {
                $changes[$field] = $input->optional($field);
            }
        }
        $tags = null;
        if ($input->has('tagList')) {
            if ($input->data['tagList'] === null) {
                $input->add('tagList', "can't be null");
            } else {
                $tags = $input->stringList('tagList');
            }
        }
        $input->validate();

        DB::transaction(function () use ($article, $changes, $tags) {
            if (isset($changes['title']) && $changes['title'] !== $article->title) {
                $changes['slug'] = $this->uniqueSlug($changes['title']);
            }
            $article->fill($changes);
            // Changing only the tags still counts as an update of the article.
            $tags !== null ? $article->touch() : $article->save();
            if ($tags !== null) {
                $this->syncTags($article, $tags);
            }
        });

        return new JsonResponse(Presenter::article($article, $viewer));
    }

    public function destroy(Request $request, string $slug): JsonResponse
    {
        $article = $this->find($slug);
        if ($article->author_id !== $request->attributes->get('user')->id) {
            throw ApiException::forbidden('article');
        }
        $article->delete();

        return new JsonResponse(null, 204);
    }

    public function favorite(Request $request, string $slug): JsonResponse
    {
        $viewer = $request->attributes->get('user');
        $article = $this->find($slug);
        DB::table('favorites')->insertOrIgnore(['user_id' => $viewer->id, 'article_id' => $article->id]);

        return new JsonResponse(Presenter::article($article, $viewer));
    }

    public function unfavorite(Request $request, string $slug): JsonResponse
    {
        $viewer = $request->attributes->get('user');
        $article = $this->find($slug);
        DB::table('favorites')->where(['user_id' => $viewer->id, 'article_id' => $article->id])->delete();

        return new JsonResponse(Presenter::article($article, $viewer));
    }

    private function paginate(Request $request, Builder $query): JsonResponse
    {
        $viewer = $request->attributes->get('user');
        $limit = $this->intParam($request, 'limit', self::DEFAULT_LIMIT, 0, self::MAX_LIMIT);
        $offset = $this->intParam($request, 'offset', 0, 0, PHP_INT_MAX);

        $count = (clone $query)->count();
        $articles = Presenter::withArticleData($query, $viewer)
            ->orderByDesc('articles.id')
            ->limit($limit)
            ->offset($offset)
            ->get();

        return new JsonResponse(Presenter::articles($articles, $viewer, $count));
    }

    private function intParam(Request $request, string $name, int $default, int $min, int $max): int
    {
        $value = $request->query($name);
        if (! is_string($value) || ! preg_match('/^\d+$/', $value)) {
            return $default;
        }

        return max($min, min($max, (int) $value));
    }

    private function find(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiException::notFound('article');
    }

    private function uniqueSlug(string $title): string
    {
        $base = Str::slug($title) ?: 'article';
        $slug = $base;
        while (Article::where('slug', $slug)->exists()) {
            $slug = $base.'-'.Str::lower(Str::random(6));
        }

        return $slug;
    }

    /** @param list<string> $names Tag names in the order they were given. */
    private function syncTags(Article $article, array $names): void
    {
        DB::table('article_tag')->where('article_id', $article->id)->delete();

        $names = array_values(array_unique(array_filter(array_map('trim', $names), fn ($n) => $n !== '')));
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
}
