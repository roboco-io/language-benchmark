<?php

namespace App\Http\Controllers;

use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Validator;
use Illuminate\Validation\ValidationException;

/** Shared helpers for the API controllers. */
abstract class Api extends Controller
{
    protected function viewer(Request $request): ?User
    {
        return $request->attributes->get('auth_user');
    }

    protected function error(string $field, string $message, int $status): JsonResponse
    {
        return response()->json(['errors' => [$field => [$message]]], $status);
    }

    /**
     * Validate $data; "required" failures read "can't be blank" and are reported as 422.
     *
     * @throws ValidationException
     */
    protected function check(array $data, array $rules): array
    {
        $validator = Validator::make($data, $rules, [
            'required' => "can't be blank",
            'filled' => "can't be blank",
            'min' => 'is too short (minimum is :min characters)',
            'email' => 'is invalid',
        ]);

        return $validator->validate();
    }
}
