<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Http\Presenter;
use App\Models\Article;
use App\Models\Comment;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;

class CommentController extends Controller
{
    public function index(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        $comments = $article->comments()->with('author')->orderBy('id')->get();

        $viewer = $this->viewer($request);
        $following = $viewer === null ? [] : DB::table('follows')
            ->where('follower_id', $viewer->id)
            ->whereIn('followee_id', $comments->pluck('author_id')->unique()->all())
            ->pluck('followee_id')
            ->mapWithKeys(fn ($id) => [(int) $id => true])
            ->all();

        return response()->json([
            'comments' => $comments->map(
                fn (Comment $comment) => Presenter::comment($comment, isset($following[$comment->author_id]))
            )->all(),
        ]);
    }

    public function store(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        $data = $this->payload($request, 'comment');
        $errors = [];
        $body = $this->requireString($data, 'body', $errors);
        $this->failIfErrors($errors);

        $viewer = $this->viewer($request);
        $comment = Comment::create(['body' => $body, 'article_id' => $article->id, 'author_id' => $viewer->id]);
        $comment->setRelation('author', $viewer);

        return response()->json(['comment' => Presenter::comment($comment, false)], 201);
    }

    public function update(Request $request, string $slug, string $id): JsonResponse
    {
        $comment = $this->findOwnComment($request, $slug, $id);
        $data = $this->payload($request, 'comment');
        $errors = [];
        $body = $this->requireString($data, 'body', $errors);
        $this->failIfErrors($errors);

        $comment->body = $body;
        // Always refresh updatedAt, even when the body is unchanged.
        $comment->updateTimestamps();
        $comment->save();
        $comment->setRelation('author', $this->viewer($request));

        return response()->json(['comment' => Presenter::comment($comment, false)]);
    }

    public function destroy(Request $request, string $slug, string $id): Response
    {
        $this->findOwnComment($request, $slug, $id)->delete();

        return response()->noContent();
    }

    /**
     * Finds a comment on the given article and ensures the viewer is its author.
     */
    private function findOwnComment(Request $request, string $slug, string $id): Comment
    {
        $article = $this->findArticle($slug);
        $comment = ctype_digit($id) ? $article->comments()->whereKey((int) $id)->first() : null;

        if ($comment === null) {
            throw ApiException::notFound('comment');
        }

        if ($comment->author_id !== $this->viewer($request)->id) {
            throw ApiException::forbidden('comment');
        }

        return $comment;
    }

    private function findArticle(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiException::notFound('article');
    }
}
