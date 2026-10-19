<?php

namespace App\Support;

use App\Models\Article;
use App\Models\Comment;
use App\Models\User;
use Illuminate\Support\Facades\DB;

class Presenter
{
    public static function iso($date): string
    {
        return $date->copy()->utc()->format('Y-m-d\TH:i:s.v\Z');
    }

    public static function user(User $user, ?string $token = null): array
    {
        return ['user' => [
            'email' => $user->email,
            'token' => $token ?? JwtToken::issue($user->id),
            'username' => $user->username,
            'bio' => $user->bio,
            'image' => $user->image,
        ]];
    }

    public static function profileData(User $user, ?User $viewer): array
    {
        $following = $viewer
            ? DB::table('follows')->where('follower_id', $viewer->id)->where('followed_id', $user->id)->exists()
            : false;

        return [
            'username' => $user->username,
            'bio' => $user->bio,
            'image' => $user->image,
            'following' => $following,
        ];
    }

    public static function article(Article $a, ?User $viewer, bool $withBody = true): array
    {
        $a->loadMissing(['author', 'tags']);
        $favorited = $viewer
            ? DB::table('favorites')->where('user_id', $viewer->id)->where('article_id', $a->id)->exists()
            : false;
        $count = DB::table('favorites')->where('article_id', $a->id)->count();

        $out = [
            'slug' => $a->slug,
            'title' => $a->title,
            'description' => $a->description,
        ];
        if ($withBody) {
            $out['body'] = $a->body;
        }

        return $out + [
            'tagList' => $a->tags->pluck('name')->values()->all(),
            'createdAt' => self::iso($a->created_at),
            'updatedAt' => self::iso($a->updated_at),
            'favorited' => $favorited,
            'favoritesCount' => $count,
            'author' => self::profileData($a->author, $viewer),
        ];
    }

    public static function comment(Comment $c, ?User $viewer): array
    {
        $c->loadMissing('author');

        return [
            'id' => $c->id,
            'createdAt' => self::iso($c->created_at),
            'updatedAt' => self::iso($c->updated_at),
            'body' => $c->body,
            'author' => self::profileData($c->author, $viewer),
        ];
    }
}
