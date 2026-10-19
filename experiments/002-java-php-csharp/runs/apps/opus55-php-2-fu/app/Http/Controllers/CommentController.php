<?php

namespace App\Http\Controllers;

use App\Models\Article;
use App\Models\Comment;
use App\Support\ApiException;
use App\Support\Input;
use App\Support\Presenter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class CommentController
{
    public function index(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        $viewer = $request->attributes->get('user');
        $comments = $article->comments()->with('author')->orderBy('id')->get();

        $followed = [];
        if ($viewer !== null && $comments->isNotEmpty()) {
            $followed = DB::table('follows')
                ->where('follower_id', $viewer->id)
                ->whereIn('followee_id', $comments->pluck('author_id')->unique()->values())
                ->pluck('followee_id')
                ->mapWithKeys(fn ($id) => [(int) $id => true])
                ->all();
        }

        return new JsonResponse([
            'comments' => $comments
                ->map(fn (Comment $c) => Presenter::comment($c, isset($followed[$c->author_id])))
                ->all(),
        ]);
    }

    public function store(Request $request, string $slug): JsonResponse
    {
        $article = $this->findArticle($slug);
        $viewer = $request->attributes->get('user');

        $input = Input::from($request, 'comment');
        $body = $input->required('body');
        $input->validate();

        $comment = Comment::create(['body' => $body, 'article_id' => $article->id, 'author_id' => $viewer->id]);
        $comment->setRelation('author', $viewer);

        return new JsonResponse(['comment' => Presenter::comment($comment, false)], 201);
    }

    public function update(Request $request, string $slug, string $id): JsonResponse
    {
        $viewer = $request->attributes->get('user');
        $comment = $this->findOwnComment($request, $this->findArticle($slug), $id);

        $input = Input::from($request, 'comment');
        $body = $input->required('body');
        $input->validate();

        $comment->body = $body;
        // Refresh updatedAt even when the body is unchanged.
        $comment->updated_at = $comment->freshTimestamp();
        $comment->save();
        $comment->setRelation('author', $viewer);

        return new JsonResponse(['comment' => Presenter::comment($comment, false)]);
    }

    public function destroy(Request $request, string $slug, string $id): JsonResponse
    {
        $this->findOwnComment($request, $this->findArticle($slug), $id)->delete();

        return new JsonResponse(null, 204);
    }

    /**
     * Finds a comment of the article and checks that the current user wrote it.
     */
    private function findOwnComment(Request $request, Article $article, string $id): Comment
    {
        $comment = ctype_digit($id)
            ? Comment::where('article_id', $article->id)->whereKey((int) $id)->first()
            : null;

        if ($comment === null) {
            throw ApiException::notFound('comment');
        }
        if ($comment->author_id !== $request->attributes->get('user')->id) {
            throw ApiException::forbidden('comment');
        }

        return $comment;
    }

    private function findArticle(string $slug): Article
    {
        return Article::where('slug', $slug)->first() ?? throw ApiException::notFound('article');
    }
}
