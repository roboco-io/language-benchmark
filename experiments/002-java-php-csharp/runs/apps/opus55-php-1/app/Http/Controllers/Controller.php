<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\ApiError;
use Illuminate\Http\Request;

abstract class Controller
{
    protected function currentUser(Request $request): ?User
    {
        return $request->attributes->get('user');
    }

    /**
     * Returns the JSON object nested under $key (for example "user" or "article").
     */
    protected function payload(Request $request, string $key): array
    {
        $data = $request->json()->all()[$key] ?? null;
        if (! is_array($data) || array_is_list($data) && $data !== []) {
            throw ApiError::validation([$key => ['is missing']]);
        }

        return $data;
    }

    /**
     * Collects "can't be blank" errors for required string fields.
     *
     * @param  list<string>  $fields
     * @return array<string, list<string>>
     */
    protected function blankErrors(array $data, array $fields): array
    {
        $errors = [];
        foreach ($fields as $field) {
            $value = $data[$field] ?? null;
            if (! is_string($value) || trim($value) === '') {
                $errors[$field] = ["can't be blank"];
            }
        }

        return $errors;
    }
}
