<?php

namespace App\Support;

use Illuminate\Http\Request;

/**
 * Minimal validator that produces RealWorld-style messages ("can't be blank", ...).
 */
class Input
{
    /** @var array<string, list<string>> */
    private array $errors = [];

    /** @param array<string, mixed> $data */
    private function __construct(public readonly array $data) {}

    /** Reads the wrapper object of the JSON body, e.g. {"user": {...}}. */
    public static function from(Request $request, string $wrapper): self
    {
        $data = $request->json($wrapper);

        return new self(is_array($data) ? $data : []);
    }

    public function has(string $field): bool
    {
        return array_key_exists($field, $this->data);
    }

    /** A field that must be present and be a non-blank string. */
    public function required(string $field): ?string
    {
        return $this->string($field, true);
    }

    /** A field that may be absent; when present it must be a non-blank string. */
    public function optional(string $field): ?string
    {
        return $this->has($field) ? $this->string($field, true) : null;
    }

    /** A field that may be absent, null or empty; empty strings are normalized to null. */
    public function nullable(string $field): ?string
    {
        $value = $this->data[$field] ?? null;
        if ($value === null || $value === '') {
            return null;
        }
        if (! is_string($value)) {
            $this->add($field, 'must be a string');

            return null;
        }

        return $value;
    }

    /** @return list<string>|null A list of strings when present, null when absent. */
    public function stringList(string $field): ?array
    {
        if (! $this->has($field)) {
            return null;
        }
        $value = $this->data[$field];
        if (! is_array($value) || ! array_is_list($value)) {
            $this->add($field, 'must be an array');

            return null;
        }
        foreach ($value as $item) {
            if (! is_string($item)) {
                $this->add($field, 'must be an array of strings');

                return null;
            }
        }

        return $value;
    }

    public function minLength(string $field, ?string $value, int $min): void
    {
        if ($value !== null && mb_strlen($value) < $min) {
            $this->add($field, "is too short (minimum is {$min} characters)");
        }
    }

    public function email(string $field, ?string $value): void
    {
        if ($value !== null && filter_var($value, FILTER_VALIDATE_EMAIL) === false) {
            $this->add($field, 'is invalid');
        }
    }

    public function add(string $field, string $message): void
    {
        $this->errors[$field][] = $message;
    }

    /** Throws a 422 response when any error was collected. */
    public function validate(): void
    {
        if ($this->errors !== []) {
            throw new ApiException(422, $this->errors);
        }
    }

    private function string(string $field, bool $required): ?string
    {
        $value = $this->data[$field] ?? null;
        if (! is_string($value) || trim($value) === '') {
            if ($required) {
                $this->add($field, is_string($value) || $value === null ? "can't be blank" : 'must be a string');
            }

            return null;
        }

        return $value;
    }
}
