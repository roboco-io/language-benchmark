<?php

namespace App\Http\Controllers;

use App\Models\Tag;

class TagController extends Api
{
    public function index()
    {
        return response()->json(['tags' => Tag::orderBy('name')->pluck('name')->all()]);
    }
}
