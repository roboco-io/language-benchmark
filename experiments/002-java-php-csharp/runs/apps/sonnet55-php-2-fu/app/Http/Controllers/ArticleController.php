<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Tag;
use App\Support\ApiException;
use App\Support\Serializer;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class ArticleController
{
    public function index(Request $request): JsonResponse
    {
        $query = Article::query();

        if ($tag = $request->query('tag')) {
            $query->whereHas('tags', fn (Builder $q) => $q->where('name', $tag));
        }
        if ($author = $request->query('author')) {
            $query->whereHas('author', fn (Builder $q) => $q->where('username', $author));
        }
        if ($favorited = $request->query('favorited')) {
            $query->whereHas('favoritedBy', fn (Builder $q) => $q->where('username', $favorited));
        }

        return $this->page($request, $query);
    }

    public function feed(Request $request): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $query = Article::query()->whereIn(
            'user_id',
            DB::table('follows')->where('follower_id', $viewer->id)->select('followee_id'),
        );

        return $this->page($request, $query);
    }

    public function show(Request $request, string $slug): JsonResponse
    {
        return response()->json([
            'article' => Serializer::article($this->find($slug), $request->attributes->get('auth_user')),
        ]);
    }

    public function store(Request $request): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $data = (array) $request->input('article', []);
        $errors = [];
        foreach (['title', 'description', 'body'] as $field) {
            if (! is_string($data[$field] ?? null) || trim($data[$field]) === '') {
                $errors[$field] = ["can't be blank"];
            }
        }
        if (array_key_exists('tagList', $data) && ! is_array($data['tagList'])) {
            $errors['tagList'] = ['must be an array'];
        }
        if ($errors) {
            throw new ApiException(422, $errors);
        }

        $article = DB::transaction(function () use ($viewer, $data) {
            $article = Article::create([
                'user_id' => $viewer->id,
                'slug' => $this->uniqueSlug($data['title']),
                'title' => $data['title'],
                'description' => $data['description'],
                'body' => $data['body'],
            ]);
            $this->syncTags($article, $data['tagList'] ?? []);

            return $article;
        });

        return response()->json(['article' => Serializer::article($article, $viewer)], 201);
    }

    public function update(Request $request, string $slug): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $article = $this->find($slug);
        $this->authorize($article, $viewer);

        $data = (array) $request->input('article', []);
        $errors = [];
        foreach (['title', 'description', 'body'] as $field) {
            if (array_key_exists($field, $data) && (! is_string($data[$field]) || trim($data[$field]) === '')) {
                $errors[$field] = ["can't be blank"];
            }
        }
        if (array_key_exists('tagList', $data) && ! is_array($data['tagList'])) {
            $errors['tagList'] = ['must be an array'];
        }
        if ($errors) {
            throw new ApiException(422, $errors);
        }

        if (isset($data['title']) && $data['title'] !== $article->title) {
            $article->title = $data['title'];
            $article->slug = $this->uniqueSlug($data['title']);
        }
        foreach (['description', 'body'] as $field) {
            if (isset($data[$field])) {
                $article->{$field} = $data[$field];
            }
        }
        DB::transaction(function () use ($article, $data) {
            $article->save();
            if (array_key_exists('tagList', $data)) {
                $this->syncTags($article, $data['tagList']);
            }
        });

        return response()->json(['article' => Serializer::article($article->refresh(), $viewer)]);
    }

    public function destroy(Request $request, string $slug)
    {
        $article = $this->find($slug);
        $this->authorize($article, $request->attributes->get('auth_user'));
        $article->delete();

        return response()->noContent();
    }

    public function favorite(Request $request, string $slug): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $article = $this->find($slug);
        $viewer->favorites()->syncWithoutDetaching([$article->id]);

        return response()->json(['article' => Serializer::article($article, $viewer)]);
    }

    public function unfavorite(Request $request, string $slug): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $article = $this->find($slug);
        $viewer->favorites()->detach($article->id);

        return response()->json(['article' => Serializer::article($article, $viewer)]);
    }

    public function tags(): JsonResponse
    {
        return response()->json(['tags' => Tag::orderBy('name')->pluck('name')->all()]);
    }

    private function page(Request $request, Builder $query): JsonResponse
    {
        $limit = max(0, min(100, (int) $request->query('limit', 20)));
        $offset = max(0, (int) $request->query('offset', 0));

        $count = (clone $query)->count();
        $articles = $query->orderByDesc('id')->limit($limit)->offset($offset)->get();

        return response()->json([
            'articles' => Serializer::articles($articles, $request->attributes->get('auth_user'), withBody: false),
            'articlesCount' => $count,
        ]);
    }

    private function find(string $slug): Article
    {
        return Article::where('slug', $slug)->first()
            ?? throw ApiException::field(404, 'article', 'not found');
    }

    private function authorize(Article $article, $viewer): void
    {
        if ($article->user_id !== $viewer->id) {
            throw ApiException::field(403, 'article', 'forbidden');
        }
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

    private function syncTags(Article $article, mixed $names): void
    {
        $ids = [];
        foreach (array_unique(array_filter((array) $names, 'is_string')) as $name) {
            $ids[] = Tag::firstOrCreate(['name' => $name])->id;
        }
        $article->tags()->sync($ids);
    }
}
