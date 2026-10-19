<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\ApiException;
use App\Support\Serializer;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class ProfileController
{
    public function show(Request $request, string $username): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');

        return response()->json(['profile' => Serializer::profile($this->find($username), $viewer)]);
    }

    public function follow(Request $request, string $username): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $target = $this->find($username);
        $viewer->following()->syncWithoutDetaching([$target->id]);

        return response()->json(['profile' => Serializer::profile($target, $viewer, true)]);
    }

    public function unfollow(Request $request, string $username): JsonResponse
    {
        $viewer = $request->attributes->get('auth_user');
        $target = $this->find($username);
        $viewer->following()->detach($target->id);

        return response()->json(['profile' => Serializer::profile($target, $viewer, false)]);
    }

    private function find(string $username): User
    {
        return User::where('username', $username)->first()
            ?? throw ApiException::field(404, 'profile', 'not found');
    }
}
