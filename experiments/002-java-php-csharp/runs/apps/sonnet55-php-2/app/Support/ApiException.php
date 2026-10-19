<?php

namespace App\Support;

use Exception;
use Illuminate\Http\JsonResponse;

/** An error that renders as the RealWorld `{"errors": {field: [message]}}` body. */
class ApiException extends Exception
{
    public function __construct(public int $status, public array $errors)
    {
        parent::__construct('API error');
    }

    public static function field(int $status, string $field, string $message): self
    {
        return new self($status, [$field => [$message]]);
    }

    public function render(): JsonResponse
    {
        return response()->json(['errors' => $this->errors], $this->status);
    }
}
