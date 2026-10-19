<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Comment;
use App\Support\ApiError;
use App\Support\Present;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;

class CommentController extends Controller
{
    public function index(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        $comments = Comment::with('author')->where('article_id', $article->id)->orderBy('id')->get();
        $followed = Present::followedIds($this->currentUser($request), $comments->pluck('author_id')->all());

        return response()->json([
            'comments' => $comments->map(fn (Comment $c) => Present::comment($c, $followed))->all(),
        ]);
    }

    public function store(Request $request, string $slug): JsonResponse
    {
        $viewer = $this->currentUser($request);
        $article = $this->findArticle($slug);
        $data = $this->payload($request, 'comment');
        if ($errors = $this->blankErrors($data, ['body'])) {
            throw ApiError::validation($errors);
        }

        $comment = Comment::create([
            'body' => $data['body'],
            'article_id' => $article->id,
            'author_id' => $viewer->id,
        ]);
        $comment->setRelation('author', $viewer);

        return response()->json(['comment' => Present::comment($comment, [])], 201);
    }

    public function destroy(Request $request, string $slug, string $id): Response
    {
        $article = $this->findArticle($slug);
        $comment = ctype_digit($id)
            ? Comment::where('article_id', $article->id)->whereKey((int) $id)->first()
            : null;
        if ($comment === null) {
            throw ApiError::notFound('comment');
        }
        if ($comment->author_id !== $this->currentUser($request)->id) {
            throw ApiError::forbidden('comment');
        }
        $comment->delete();

        return response()->noContent();
    }

    private function findArticle(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiError::notFound('article');
    }
}
