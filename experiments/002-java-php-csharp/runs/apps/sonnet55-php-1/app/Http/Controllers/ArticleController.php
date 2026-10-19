<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Models\Article;
use App\Models\Tag;
use App\Support\Presenter;
use Illuminate\Database\Eloquent\Builder;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class ArticleController extends Controller
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
        $viewer = $this->viewer($request);
        $query = Article::query()->whereIn('user_id', $viewer->following()->select('users.id'));

        return $this->page($request, $query);
    }

    public function show(Request $request, string $slug): JsonResponse
    {
        return $this->respond($this->find($slug), $request);
    }

    public function store(Request $request): JsonResponse
    {
        $data = $this->payload($request, 'article', ['title', 'description', 'body']);
        $tags = $this->tagList($data);

        $article = DB::transaction(function () use ($request, $data, $tags) {
            $article = Article::create([
                'user_id' => $this->viewer($request)->id,
                'slug' => $this->uniqueSlug($data['title']),
                'title' => $data['title'],
                'description' => $data['description'],
                'body' => $data['body'],
            ]);
            $this->syncTags($article, $tags ?? []);

            return $article;
        });

        return $this->respond($article, $request, 201);
    }

    public function update(Request $request, string $slug): JsonResponse
    {
        $article = $this->find($slug);
        $this->authorize($request, $article);
        $data = $this->payload($request, 'article', [], ['title', 'description', 'body']);
        $tags = $this->tagList($data);

        DB::transaction(function () use ($article, $data, $tags) {
            $fill = array_intersect_key($data, array_flip(['title', 'description', 'body']));
            if (isset($fill['title']) && $fill['title'] !== $article->title) {
                $fill['slug'] = $this->uniqueSlug($fill['title']);
            }
            $article->fill($fill)->save();
            if ($tags !== null) {
                $this->syncTags($article, $tags);
            }
            $article->touch();
        });

        return $this->respond($article, $request);
    }

    public function destroy(Request $request, string $slug): JsonResponse
    {
        $article = $this->find($slug);
        $this->authorize($request, $article);
        $article->delete();

        return response()->json(null, 204);
    }

    public function favorite(Request $request, string $slug): JsonResponse
    {
        $article = $this->find($slug);
        $article->favoritedBy()->syncWithoutDetaching([$this->viewer($request)->id]);

        return $this->respond($article, $request);
    }

    public function unfavorite(Request $request, string $slug): JsonResponse
    {
        $article = $this->find($slug);
        $article->favoritedBy()->detach($this->viewer($request)->id);

        return $this->respond($article, $request);
    }

    public function tags(): JsonResponse
    {
        return response()->json(['tags' => Tag::orderBy('name')->pluck('name')]);
    }

    private function page(Request $request, Builder $query): JsonResponse
    {
        $limit = max(0, min(100, (int) $request->query('limit', 20)));
        $offset = max(0, (int) $request->query('offset', 0));

        $count = (clone $query)->count();
        $articles = $query->with(['author', 'tags'])->withCount('favoritedBy')
            ->orderByDesc('created_at')->orderByDesc('id')
            ->limit($limit)->offset($offset)->get();

        return response()->json([
            'articles' => Presenter::articles($articles, $this->viewer($request), withBody: false),
            'articlesCount' => $count,
        ]);
    }

    private function find(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiException::notFound('article');
    }

    private function authorize(Request $request, Article $article): void
    {
        if ($article->user_id !== $this->viewer($request)->id) {
            throw ApiException::forbidden('article');
        }
    }

    private function respond(Article $article, Request $request, int $status = 200): JsonResponse
    {
        return response()->json(['article' => Presenter::article($article, $this->viewer($request))], $status);
    }

    /** @return list<string>|null null when the client did not send a tagList */
    private function tagList(array $data): ?array
    {
        if (! array_key_exists('tagList', $data)) {
            return null;
        }
        $list = $data['tagList'];
        if (! is_array($list) || array_filter($list, fn ($t) => ! is_string($t) || trim($t) === '')) {
            throw new ApiException(422, ['tagList' => ['is invalid']]);
        }

        return array_values(array_unique(array_map('trim', $list)));
    }

    private function syncTags(Article $article, array $names): void
    {
        $ids = [];
        foreach ($names as $name) {
            $ids[] = Tag::firstOrCreate(['name' => $name])->id;
        }
        $article->tags()->sync($ids);
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
}
