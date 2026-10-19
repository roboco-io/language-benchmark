<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Comment;
use App\Support\Presenter;
use Illuminate\Http\Request;

class CommentController extends Api
{
    public function index(Request $request, string $slug)
    {
        $article = Article::where('slug', $slug)->first();
        if (! $article) {
            return $this->error('article', 'not found', 404);
        }

        $viewer = $this->viewer($request);
        $comments = $article->comments()->with('author')->orderBy('id')->get()
            ->map(fn ($c) => Presenter::comment($c, $viewer))->all();

        return response()->json(['comments' => $comments]);
    }

    public function store(Request $request, string $slug)
    {
        $article = Article::where('slug', $slug)->first();
        if (! $article) {
            return $this->error('article', 'not found', 404);
        }
        $data = $this->check((array) $request->input('comment', []), ['body' => ['required', 'string']]);

        $viewer = $this->viewer($request);
        $comment = $article->comments()->create(['user_id' => $viewer->id, 'body' => $data['body']]);

        return response()->json(['comment' => Presenter::comment($comment, $viewer)], 201);
    }

    public function destroy(Request $request, string $slug, string $id)
    {
        $article = Article::where('slug', $slug)->first();
        if (! $article) {
            return $this->error('article', 'not found', 404);
        }
        $comment = ctype_digit($id) ? $article->comments()->find((int) $id) : null;
        if (! $comment) {
            return $this->error('comment', 'not found', 404);
        }
        if ($comment->user_id !== $this->viewer($request)->id) {
            return $this->error('comment', 'forbidden', 403);
        }
        $comment->delete();

        return response()->noContent();
    }
}
