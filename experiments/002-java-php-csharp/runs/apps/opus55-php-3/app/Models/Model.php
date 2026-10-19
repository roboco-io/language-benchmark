<?php

namespace App\Models;

use DateTimeInterface;
use Illuminate\Database\Eloquent\Model as EloquentModel;

/**
 * Base model: timestamps are stored with microsecond precision so that consecutive
 * updates always produce distinct updatedAt values.
 */
abstract class Model extends EloquentModel
{
    protected $dateFormat = 'Y-m-d H:i:s.u';

    public static function isoDate(?DateTimeInterface $date): ?string
    {
        return $date?->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d\TH:i:s.v\Z');
    }
}
