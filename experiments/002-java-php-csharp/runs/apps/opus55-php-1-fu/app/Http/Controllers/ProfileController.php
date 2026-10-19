<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\ApiError;
use App\Support\Present;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class ProfileController extends Controller
{
    public function show(Request $request, string $username): JsonResponse
    {
        $profile = $this->findProfile($username);
        $following = Present::followedIds($this->currentUser($request), [$profile->id]) !== [];

        return response()->json(['profile' => Present::profile($profile, $following)]);
    }

    public function follow(Request $request, string $username): JsonResponse
    {
        $viewer = $this->currentUser($request);
        $profile = $this->findProfile($username);
        DB::table('follows')->insertOrIgnore(['follower_id' => $viewer->id, 'followee_id' => $profile->id]);

        return response()->json(['profile' => Present::profile($profile, true)]);
    }

    public function unfollow(Request $request, string $username): JsonResponse
    {
        $viewer = $this->currentUser($request);
        $profile = $this->findProfile($username);
        DB::table('follows')->where(['follower_id' => $viewer->id, 'followee_id' => $profile->id])->delete();

        return response()->json(['profile' => Present::profile($profile, false)]);
    }

    private function findProfile(string $username): User
    {
        return User::where('username', $username)->first() ?? throw ApiError::notFound('profile');
    }
}
