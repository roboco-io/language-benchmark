<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class User extends Model
{
    protected $guarded = [];

    protected $dateFormat = 'Y-m-d H:i:s.v';

    protected $hidden = ['password'];

    public function articles()
    {
        return $this->hasMany(Article::class);
    }

    public function following()
    {
        return $this->belongsToMany(self::class, 'follows', 'follower_id', 'followed_id');
    }
}
