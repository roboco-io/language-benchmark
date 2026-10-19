<?php

namespace App\Http\Controllers;

use App\Models\Tag;
use Illuminate\Http\JsonResponse;

class TagController
{
    public function index(): JsonResponse
    {
        return new JsonResponse(['tags' => Tag::orderBy('name')->pluck('name')->all()]);
    }
}
