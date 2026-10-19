<?php

namespace App\Support;

use Illuminate\Http\JsonResponse;
use RuntimeException;

/**
 * An error that is rendered as a RealWorld error body: {"errors": {"field": ["message"]}}.
 */
class ApiException extends RuntimeException
{
    /** @param array<string, list<string>> $errors */
    public function __construct(public readonly int $status, public readonly array $errors)
    {
        parent::__construct(json_encode($errors));
    }

    public static function make(int $status, string $field, string $message): self
    {
        return new self($status, [$field => [$message]]);
    }

    public static function notFound(string $field): self
    {
        return self::make(404, $field, 'not found');
    }

    public static function forbidden(string $field): self
    {
        return self::make(403, $field, 'forbidden');
    }

    public function render(): JsonResponse
    {
        return new JsonResponse(['errors' => $this->errors], $this->status);
    }
}
