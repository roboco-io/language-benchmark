<?php

namespace App\Http\Controllers;

use App\Models\Tag;
use Illuminate\Http\JsonResponse;

class TagController extends Controller
{
    public function index(): JsonResponse
    {
        // Only list tags that are still attached to at least one article.
        $tags = Tag::query()
            ->whereExists(fn ($q) => $q->from('article_tag')->whereColumn('article_tag.tag_id', 'tags.id'))
            ->orderBy('name')
            ->pluck('name');

        return response()->json(['tags' => $tags->all()]);
    }
}
