<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Article extends Model
{
    protected $fillable = ['slug', 'title', 'description', 'body', 'author_id'];

    public function author(): BelongsTo
    {
        return $this->belongsTo(User::class, 'author_id');
    }

    /** Tags in the order they were given in the tagList. */
    public function tags(): BelongsToMany
    {
        return $this->belongsToMany(Tag::class)->orderBy('article_tag.id');
    }

    public function favoritedBy(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'favorites');
    }

    public function comments(): HasMany
    {
        return $this->hasMany(Comment::class);
    }
}
