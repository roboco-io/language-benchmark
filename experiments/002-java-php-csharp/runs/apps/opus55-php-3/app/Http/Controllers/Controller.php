<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Models\User;
use Illuminate\Http\Request;

abstract class Controller
{
    /** The authenticated user, or null for anonymous requests. */
    protected function viewer(Request $request): ?User
    {
        return $request->attributes->get('user');
    }

    /**
     * Returns the object nested under $wrapper in the JSON body, e.g. {"user": {...}}.
     *
     * @return array<string, mixed>
     */
    protected function payload(Request $request, string $wrapper): array
    {
        $data = $request->json($wrapper);

        return is_array($data) ? $data : [];
    }

    /**
     * Collects a "can't be blank" error unless $data[$key] is a non-blank string.
     *
     * @param  array<string, list<string>>  $errors
     */
    protected function requireString(array $data, string $key, array &$errors): ?string
    {
        $value = $data[$key] ?? null;

        if (! is_string($value) || trim($value) === '') {
            $errors[$key][] = "can't be blank";

            return null;
        }

        return $value;
    }

    /**
     * @param  array<string, list<string>>  $errors
     */
    protected function failIfErrors(array $errors): void
    {
        if ($errors !== []) {
            throw ApiException::unprocessable($errors);
        }
    }
}
