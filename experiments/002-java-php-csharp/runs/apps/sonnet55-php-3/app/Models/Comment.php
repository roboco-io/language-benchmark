<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class Comment extends Model
{
    protected $guarded = [];

    protected $dateFormat = 'Y-m-d H:i:s.v';

    public function author()
    {
        return $this->belongsTo(User::class, 'user_id');
    }
}
