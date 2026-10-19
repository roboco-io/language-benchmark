<?php

namespace App\Exceptions;

use RuntimeException;

/** An error that renders as the RealWorld `{"errors": {...}}` body. */
class ApiException extends RuntimeException
{
    /** @param array<string, list<string>> $errors */
    public function __construct(public readonly int $status, public readonly array $errors)
    {
        parent::__construct('API error '.$status);
    }

    public static function unauthorized(): self
    {
        return new self(401, ['token' => ['is missing']]);
    }

    public static function notFound(string $what): self
    {
        return new self(404, [$what => ['not found']]);
    }

    public static function forbidden(string $what): self
    {
        return new self(403, [$what => ['forbidden']]);
    }
}
