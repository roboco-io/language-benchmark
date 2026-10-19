<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Models\User;
use App\Support\Presenter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class ProfileController extends Controller
{
    public function show(Request $request, string $username): JsonResponse
    {
        $user = $this->find($username);
        $viewer = $this->viewer($request);

        return $this->respond($user, (bool) $viewer && $user->followers()->whereKey($viewer->id)->exists());
    }

    public function follow(Request $request, string $username): JsonResponse
    {
        $user = $this->find($username);
        $this->viewer($request)->following()->syncWithoutDetaching([$user->id]);

        return $this->respond($user, true);
    }

    public function unfollow(Request $request, string $username): JsonResponse
    {
        $user = $this->find($username);
        DB::table('follows')->where('follower_id', $this->viewer($request)->id)->where('followed_id', $user->id)->delete();

        return $this->respond($user, false);
    }

    private function find(string $username): User
    {
        return User::where('username', $username)->first() ?? throw ApiException::notFound('profile');
    }

    private function respond(User $user, bool $following): JsonResponse
    {
        return response()->json(['profile' => Presenter::profile($user, $following)]);
    }
}
