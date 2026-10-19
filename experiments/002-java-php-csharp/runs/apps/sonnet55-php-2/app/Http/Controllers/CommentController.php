<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Comment;
use App\Support\ApiException;
use App\Support\Serializer;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class CommentController
{
    public function index(Request $request, string $slug): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $comments = $this->article($slug)->comments()->with('author')->orderBy('id')->get();

        return response()->json([
            'comments' => $comments->map(fn (Comment $c) => Serializer::comment($c, $viewer))->all(),
        ]);
    }

    public function store(Request $request, string $slug): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $article = $this->article($slug);
        $body = $request->input('comment.body');
        if (! is_string($body) || trim($body) === '') {
            throw ApiException::field(422, 'body', "can't be blank");
        }

        $comment = $article->comments()->create(['user_id' => $viewer->id, 'body' => $body]);

        return response()->json(['comment' => Serializer::comment($comment->load('author'), $viewer)], 201);
    }

    public function destroy(Request $request, string $slug, int $id)
    {
        $viewer = $request->attributes->get('auth_user');
        $article = $this->article($slug);
        $comment = $article->comments()->find($id)
            ?? throw ApiException::field(404, 'comment', 'not found');
        if ($comment->user_id !== $viewer->id) {
            throw ApiException::field(403, 'comment', 'forbidden');
        }
        $comment->delete();

        return response()->noContent();
    }

    private function article(string $slug): Article
    {
        return Article::where('slug', $slug)->first()
            ?? throw ApiException::field(404, 'article', 'not found');
    }
}
