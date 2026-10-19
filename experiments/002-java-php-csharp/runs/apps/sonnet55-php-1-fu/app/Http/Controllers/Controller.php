<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Models\User;
use Illuminate\Http\Request;

abstract class Controller
{
    protected function viewer(Request $request): ?User
    {
        return $request->attributes->get('auth_user');
    }

    /**
     * Reads the wrapped JSON object (e.g. `{"user": {...}}`) and checks it.
     * `required` fields must be present and non-blank; `optional` fields are
     * only checked when present. Both lists name string fields.
     *
     * @return array<string, mixed>
     */
    protected function payload(Request $request, string $key, array $required, array $optional = []): array
    {
        $data = $request->input($key);
        $data = is_array($data) ? $data : [];
        $errors = [];

        foreach ($required as $field) {
            if (! $this->filled($data[$field] ?? null)) {
                $errors[$field][] = "can't be blank";
            }
        }
        foreach ($optional as $field) {
            if (array_key_exists($field, $data) && ! $this->filled($data[$field])) {
                $errors[$field][] = "can't be blank";
            }
        }
        if ($errors) {
            throw new ApiException(422, $errors);
        }

        return $data;
    }

    private function filled(mixed $value): bool
    {
        return is_string($value) && trim($value) !== '';
    }
}
