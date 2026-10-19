<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Illuminate\Database\Eloquent\Relations\HasMany;

class User extends Model
{
    protected $fillable = ['username', 'email', 'password', 'bio', 'image'];

    protected $hidden = ['password'];

    protected function casts(): array
    {
        return ['password' => 'hashed'];
    }

    public function articles(): HasMany
    {
        return $this->hasMany(Article::class, 'author_id');
    }

    /** Users that this user follows. */
    public function following(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'follows', 'follower_id', 'followee_id');
    }

    public function favorites(): BelongsToMany
    {
        return $this->belongsToMany(Article::class, 'favorites');
    }

    public function isFollowing(User $other): bool
    {
        return $this->following()->whereKey($other->id)->exists();
    }
}
