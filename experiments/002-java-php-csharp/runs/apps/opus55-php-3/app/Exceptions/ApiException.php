<?php

namespace App\Exceptions;

use RuntimeException;

/**
 * An error that is rendered as the RealWorld error envelope:
 * {"errors": {"<field>": ["<message>"]}}.
 */
class ApiException extends RuntimeException
{
    /**
     * @param  array<string, list<string>>  $errors
     */
    public function __construct(public readonly int $status, public readonly array $errors)
    {
        parent::__construct(json_encode($errors));
    }

    public static function field(int $status, string $field, string $message): self
    {
        return new self($status, [$field => [$message]]);
    }

    public static function notFound(string $resource): self
    {
        return self::field(404, $resource, 'not found');
    }

    public static function forbidden(string $resource): self
    {
        return self::field(403, $resource, 'forbidden');
    }

    public static function unauthorized(string $message): self
    {
        return self::field(401, 'token', $message);
    }

    /**
     * @param  array<string, list<string>>  $errors
     */
    public static function unprocessable(array $errors): self
    {
        return new self(422, $errors);
    }
}
