<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;

class User extends Model
{
    protected $dateFormat = 'Y-m-d H:i:s.v';

    protected $fillable = ['username', 'email', 'password', 'bio', 'image'];

    protected $hidden = ['password'];

    public function followers(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'follows', 'followee_id', 'follower_id');
    }

    public function favorites(): BelongsToMany
    {
        return $this->belongsToMany(Article::class, 'favorites');
    }
}
