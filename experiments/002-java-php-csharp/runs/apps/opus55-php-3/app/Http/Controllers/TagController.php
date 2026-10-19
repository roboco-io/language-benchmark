<?php

namespace App\Http\Controllers;

use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\DB;

class TagController extends Controller
{
    /** Lists every tag that is attached to at least one article. */
    public function index(): JsonResponse
    {
        $tags = DB::table('tags')
            ->whereExists(fn ($q) => $q->select(DB::raw(1))->from('article_tag')->whereColumn('article_tag.tag_id', 'tags.id'))
            ->orderBy('name')
            ->pluck('name');

        return response()->json(['tags' => $tags]);
    }
}
