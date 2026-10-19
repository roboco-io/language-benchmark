<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\ApiException;
use App\Support\Serializer;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;

class UserController
{
    public function register(Request $request): JsonResponse
    {
        $data = (array) $request->input('user', []);
        $errors = [];
        $this->checkRequired($data, ['username', 'email', 'password'], $errors);
        $this->checkFormat($data, $errors);
        if ($errors) {
            throw new ApiException(422, $errors);
        }

        $taken = [];
        if (User::where('username', $data['username'])->exists()) {
            $taken['username'] = ['has already been taken'];
        }
        if (User::where('email', $data['email'])->exists()) {
            $taken['email'] = ['has already been taken'];
        }
        if ($taken) {
            throw new ApiException(409, $taken);
        }

        $user = User::create([
            'username' => $data['username'],
            'email' => $data['email'],
            'password' => Hash::make($data['password']),
        ]);

        return response()->json(['user' => Serializer::user($user)], 201);
    }

    public function login(Request $request): JsonResponse
    {
        $data = (array) $request->input('user', []);
        $errors = [];
        $this->checkRequired($data, ['email', 'password'], $errors);
        if ($errors) {
            throw new ApiException(422, $errors);
        }

        $user = User::where('email', $data['email'])->first();
        if (! $user || ! Hash::check($data['password'], $user->password)) {
            throw ApiException::field(401, 'credentials', 'invalid');
        }

        return response()->json(['user' => Serializer::user($user)]);
    }

    public function show(Request $request): JsonResponse
    {
        return response()->json(['user' => Serializer::user($request->attributes->get('auth_user'))]);
    }

    public function update(Request $request): JsonResponse
    {
        $user = $request->attributes->get('auth_user');
        $data = (array) $request->input('user', []);
        $errors = [];

        // Present-but-blank values are rejected; absent keys are left untouched.
        foreach (['username', 'email', 'password'] as $field) {
            if (array_key_exists($field, $data) && ! $this->filled($data[$field])) {
                $errors[$field] = ["can't be blank"];
            }
        }
        $this->checkFormat($data, $errors);
        if ($errors) {
            throw new ApiException(422, $errors);
        }

        $taken = [];
        if (isset($data['username']) && User::where('username', $data['username'])->where('id', '!=', $user->id)->exists()) {
            $taken['username'] = ['has already been taken'];
        }
        if (isset($data['email']) && User::where('email', $data['email'])->where('id', '!=', $user->id)->exists()) {
            $taken['email'] = ['has already been taken'];
        }
        if ($taken) {
            throw new ApiException(409, $taken);
        }

        foreach (['username', 'email', 'bio', 'image'] as $field) {
            if (array_key_exists($field, $data)) {
                $user->{$field} = $data[$field];
            }
        }
        if (array_key_exists('password', $data)) {
            $user->password = Hash::make($data['password']);
        }
        $user->save();

        return response()->json(['user' => Serializer::user($user)]);
    }

    private function filled(mixed $value): bool
    {
        return is_string($value) && trim($value) !== '';
    }

    private function checkRequired(array $data, array $fields, array &$errors): void
    {
        foreach ($fields as $field) {
            if (! $this->filled($data[$field] ?? null)) {
                $errors[$field] = ["can't be blank"];
            }
        }
    }

    private function checkFormat(array $data, array &$errors): void
    {
        if ($this->filled($data['email'] ?? null) && ! filter_var($data['email'], FILTER_VALIDATE_EMAIL)) {
            $errors['email'] = ['is invalid'];
        }
        if ($this->filled($data['password'] ?? null) && mb_strlen($data['password']) < 8) {
            $errors['password'] = ['is too short (minimum is 8 characters)'];
        }
    }
}
