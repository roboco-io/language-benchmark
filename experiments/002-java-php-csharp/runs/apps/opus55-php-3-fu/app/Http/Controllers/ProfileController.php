<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Http\Presenter;
use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class ProfileController extends Controller
{
    public function show(Request $request, string $username): JsonResponse
    {
        $profile = $this->findUser($username);
        $viewer = $this->viewer($request);

        return $this->respond($profile, $viewer !== null && $viewer->isFollowing($profile));
    }

    public function follow(Request $request, string $username): JsonResponse
    {
        $profile = $this->findUser($username);
        $viewer = $this->viewer($request);

        if ($viewer->id !== $profile->id) {
            $viewer->following()->syncWithoutDetaching([$profile->id]);
        }

        return $this->respond($profile, $viewer->id !== $profile->id);
    }

    public function unfollow(Request $request, string $username): JsonResponse
    {
        $profile = $this->findUser($username);
        $this->viewer($request)->following()->detach($profile->id);

        return $this->respond($profile, false);
    }

    private function findUser(string $username): User
    {
        return User::where('username', $username)->first() ?? throw ApiException::notFound('profile');
    }

    private function respond(User $profile, bool $following): JsonResponse
    {
        return response()->json(['profile' => Presenter::profile($profile, $following)]);
    }
}
