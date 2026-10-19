<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Tag;
use App\Models\User;
use App\Support\Presenter;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class ArticleController extends Api
{
    public function index(Request $request)
    {
        $query = Article::query();

        if ($tag = $request->query('tag')) {
            $query->whereIn('id', DB::table('article_tag')
                ->join('tags', 'tags.id', '=', 'article_tag.tag_id')
                ->where('tags.name', $tag)->select('article_id'));
        }
        if ($author = $request->query('author')) {
            $query->whereIn('user_id', User::where('username', $author)->select('id'));
        }
        if ($favorited = $request->query('favorited')) {
            $query->whereIn('id', DB::table('favorites')
                ->join('users', 'users.id', '=', 'favorites.user_id')
                ->where('users.username', $favorited)->select('article_id'));
        }

        return $this->page($request, $query);
    }

    public function feed(Request $request)
    {
        $viewer = $this->viewer($request);
        $query = Article::query()->whereIn('user_id',
            DB::table('follows')->where('follower_id', $viewer->id)->select('followed_id'));

        return $this->page($request, $query);
    }

    public function show(Request $request, string $slug)
    {
        $article = Article::where('slug', $slug)->first();
        if (! $article) {
            return $this->error('article', 'not found', 404);
        }

        return response()->json(['article' => Presenter::article($article, $this->viewer($request))]);
    }

    public function store(Request $request)
    {
        $input = (array) $request->input('article', []);
        $data = $this->check($input, [
            'title' => ['required', 'string'],
            'description' => ['required', 'string'],
            'body' => ['required', 'string'],
            'tagList' => ['nullable', 'array'],
            'tagList.*' => ['string'],
        ]);
        $viewer = $this->viewer($request);

        $article = DB::transaction(function () use ($data, $viewer) {
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

        return response()->json(['article' => Presenter::article($article, $viewer)], 201);
    }

    public function update(Request $request, string $slug)
    {
        $article = Article::where('slug', $slug)->first();
        if (! $article) {
            return $this->error('article', 'not found', 404);
        }
        $viewer = $this->viewer($request);
        if ($article->user_id !== $viewer->id) {
            return $this->error('article', 'forbidden', 403);
        }

        $input = (array) $request->input('article', []);
        $rules = [];
        foreach (['title', 'description', 'body'] as $field) {
            if (array_key_exists($field, $input)) {
                $rules[$field] = ['required', 'string'];
            }
        }
        if (array_key_exists('tagList', $input)) {
            if ($input['tagList'] === null) {
                return response()->json(['errors' => ['tagList' => ["can't be blank"]]], 422);
            }
            $rules['tagList'] = ['array'];
            $rules['tagList.*'] = ['string'];
        }
        $data = $this->check($input, $rules);

        DB::transaction(function () use ($article, $data) {
            $fields = array_intersect_key($data, array_flip(['title', 'description', 'body']));
            if (isset($fields['title']) && $fields['title'] !== $article->title) {
                $fields['slug'] = $this->uniqueSlug($fields['title']);
            }
            $article->fill($fields)->save();
            if (array_key_exists('tagList', $data)) {
                $this->syncTags($article, $data['tagList']);
            }
        });

        return response()->json(['article' => Presenter::article($article->refresh()->load('tags'), $viewer)]);
    }

    public function destroy(Request $request, string $slug)
    {
        $article = Article::where('slug', $slug)->first();
        if (! $article) {
            return $this->error('article', 'not found', 404);
        }
        if ($article->user_id !== $this->viewer($request)->id) {
            return $this->error('article', 'forbidden', 403);
        }
        $article->delete();

        return response()->noContent();
    }

    public function favorite(Request $request, string $slug)
    {
        return $this->setFavorite($request, $slug, true);
    }

    public function unfavorite(Request $request, string $slug)
    {
        return $this->setFavorite($request, $slug, false);
    }

    private function setFavorite(Request $request, string $slug, bool $on)
    {
        $article = Article::where('slug', $slug)->first();
        if (! $article) {
            return $this->error('article', 'not found', 404);
        }
        $viewer = $this->viewer($request);
        if ($on) {
            DB::table('favorites')->insertOrIgnore(['user_id' => $viewer->id, 'article_id' => $article->id]);
        } else {
            DB::table('favorites')->where('user_id', $viewer->id)->where('article_id', $article->id)->delete();
        }

        return response()->json(['article' => Presenter::article($article, $viewer)]);
    }

    private function page(Request $request, $query)
    {
        $limit = max(0, min(100, (int) $request->query('limit', 20)));
        $offset = max(0, (int) $request->query('offset', 0));
        $viewer = $this->viewer($request);

        $total = (clone $query)->count();
        $articles = $query->with(['author', 'tags'])->orderByDesc('created_at')->orderByDesc('id')
            ->limit($limit)->offset($offset)->get()
            ->map(fn ($a) => Presenter::article($a, $viewer, false))->all();

        return response()->json(['articles' => $articles, 'articlesCount' => $total]);
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

    private function syncTags(Article $article, array $names): void
    {
        $sync = [];
        $position = 0;
        foreach (array_values(array_unique($names)) as $name) {
            $tag = Tag::firstOrCreate(['name' => $name]);
            $sync[$tag->id] = ['position' => $position++];
        }
        $article->tags()->sync($sync);
        $article->unsetRelation('tags');
    }
}
