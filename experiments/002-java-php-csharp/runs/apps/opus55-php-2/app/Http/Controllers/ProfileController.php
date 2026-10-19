<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\ApiException;
use App\Support\Presenter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class ProfileController
{
    public function show(Request $request, string $username): JsonResponse
    {
        $target = $this->find($username);
        $viewer = $request->attributes->get('user');

        return new JsonResponse(['profile' => Presenter::profile($target, Presenter::isFollowing($viewer, $target))]);
    }

    public function follow(Request $request, string $username): JsonResponse
    {
        $target = $this->find($username);
        $viewer = $request->attributes->get('user');

        DB::table('follows')->insertOrIgnore(['follower_id' => $viewer->id, 'followee_id' => $target->id]);

        return new JsonResponse(['profile' => Presenter::profile($target, true)]);
    }

    public function unfollow(Request $request, string $username): JsonResponse
    {
        $target = $this->find($username);
        $viewer = $request->attributes->get('user');

        DB::table('follows')->where(['follower_id' => $viewer->id, 'followee_id' => $target->id])->delete();

        return new JsonResponse(['profile' => Presenter::profile($target, false)]);
    }

    private function find(string $username): User
    {
        return User::where('username', $username)->first() ?? throw ApiException::notFound('profile');
    }
}
