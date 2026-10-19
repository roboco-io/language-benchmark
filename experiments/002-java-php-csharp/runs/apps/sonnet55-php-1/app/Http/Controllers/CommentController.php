<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Models\Article;
use App\Models\Comment;
use App\Support\Presenter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class CommentController extends Controller
{
    public function index(Request $request, string $slug): JsonResponse
    {
        $article = $this->article($slug);
        $comments = $article->comments()->with('author')->orderBy('created_at')->orderBy('id')->get();

        return response()->json(['comments' => Presenter::comments($comments, $this->viewer($request))]);
    }

    public function store(Request $request, string $slug): JsonResponse
    {
        $article = $this->article($slug);
        $data = $this->payload($request, 'comment', ['body']);

        $comment = $article->comments()->create(['user_id' => $this->viewer($request)->id, 'body' => $data['body']]);
        $comment->load('author');

        return response()->json(['comment' => Presenter::comments(collect([$comment]), $this->viewer($request))[0]], 201);
    }

    public function destroy(Request $request, string $slug, int $id): JsonResponse
    {
        $article = $this->article($slug);
        $comment = $article->comments()->whereKey($id)->first() ?? throw ApiException::notFound('comment');

        if ($comment->user_id !== $this->viewer($request)->id) {
            throw ApiException::forbidden('comment');
        }
        $comment->delete();

        return response()->json(null, 204);
    }

    private function article(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiException::notFound('article');
    }
}
