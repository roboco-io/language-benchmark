<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\Presenter;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class ProfileController extends Api
{
    public function show(Request $request, string $username)
    {
        $user = User::where('username', $username)->first();
        if (! $user) {
            return $this->error('profile', 'not found', 404);
        }

        return response()->json(['profile' => Presenter::profileData($user, $this->viewer($request))]);
    }

    public function follow(Request $request, string $username)
    {
        return $this->toggle($request, $username, true);
    }

    public function unfollow(Request $request, string $username)
    {
        return $this->toggle($request, $username, false);
    }

    private function toggle(Request $request, string $username, bool $follow)
    {
        $user = User::where('username', $username)->first();
        if (! $user) {
            return $this->error('profile', 'not found', 404);
        }
        $viewer = $this->viewer($request);

        if ($follow) {
            DB::table('follows')->insertOrIgnore(['follower_id' => $viewer->id, 'followed_id' => $user->id]);
        } else {
            DB::table('follows')->where('follower_id', $viewer->id)->where('followed_id', $user->id)->delete();
        }

        return response()->json(['profile' => Presenter::profileData($user, $viewer)]);
    }
}
