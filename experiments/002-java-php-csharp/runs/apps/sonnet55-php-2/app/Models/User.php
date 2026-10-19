<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;

class User extends Model
{
    protected $guarded = [];

    protected $dateFormat = 'Y-m-d H:i:s.v';

    protected $hidden = ['password'];

    public function following(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'follows', 'follower_id', 'followee_id');
    }

    public function favorites(): BelongsToMany
    {
        return $this->belongsToMany(Article::class, 'favorites');
    }
}
