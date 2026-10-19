<?php

namespace App\Support;

use Exception;
use Illuminate\Http\JsonResponse;

/**
 * An error that renders as the RealWorld error body: {"errors": {"field": ["message"]}}.
 */
class ApiError extends Exception
{
    /** @param array<string, list<string>> $errors */
    public function __construct(public readonly int $status, public readonly array $errors)
    {
        parent::__construct('API error', $status);
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

    /** @param array<string, list<string>> $errors */
    public static function validation(array $errors): self
    {
        return new self(422, $errors);
    }

    public function render(): JsonResponse
    {
        return response()->json(['errors' => $this->errors], $this->status);
    }
}
