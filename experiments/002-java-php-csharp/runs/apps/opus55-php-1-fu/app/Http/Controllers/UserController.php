<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\ApiError;
use App\Support\Present;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;

class UserController extends Controller
{
    private const MIN_PASSWORD_LENGTH = 8;

    public function register(Request $request): JsonResponse
    {
        $data = $this->payload($request, 'user');
        $errors = $this->blankErrors($data, ['username', 'email', 'password']);
        $errors += $this->formatErrors($data);
        if ($errors !== []) {
            throw ApiError::validation($errors);
        }

        $attributes = [
            'username' => trim($data['username']),
            'email' => trim($data['email']),
        ];
        $this->assertUnique($attributes);

        $user = $this->saveUnique(fn () => User::create($attributes + [
            'password' => Hash::make($data['password']),
        ]));

        return response()->json(['user' => Present::user($user)], 201);
    }

    public function login(Request $request): JsonResponse
    {
        $data = $this->payload($request, 'user');
        $errors = $this->blankErrors($data, ['email', 'password']);
        if ($errors !== []) {
            throw ApiError::validation($errors);
        }

        $user = User::where('email', trim($data['email']))->first();
        if ($user === null || ! Hash::check($data['password'], $user->password)) {
            throw ApiError::make(401, 'credentials', 'invalid');
        }

        return response()->json(['user' => Present::user($user)]);
    }

    public function show(Request $request): JsonResponse
    {
        return response()->json(['user' => Present::user($this->currentUser($request))]);
    }

    public function update(Request $request): JsonResponse
    {
        $user = $this->currentUser($request);
        $data = $this->payload($request, 'user');

        $required = array_values(array_intersect(['username', 'email', 'password'], array_keys($data)));
        $errors = $this->blankErrors($data, $required);
        $errors += $this->formatErrors($data);
        foreach (['bio', 'image'] as $field) {
            if (array_key_exists($field, $data) && $data[$field] !== null && ! is_string($data[$field])) {
                $errors[$field] = ['must be a string'];
            }
        }
        if ($errors !== []) {
            throw ApiError::validation($errors);
        }

        foreach (['username', 'email'] as $field) {
            if (array_key_exists($field, $data)) {
                $user->{$field} = trim($data[$field]);
            }
        }
        foreach (['bio', 'image'] as $field) {
            if (array_key_exists($field, $data)) {
                $user->{$field} = $data[$field] === '' ? null : $data[$field];
            }
        }
        if (array_key_exists('password', $data)) {
            $user->password = Hash::make($data['password']);
        }

        $this->assertUnique($user->only(['username', 'email']), $user->id);
        $this->saveUnique(fn () => $user->save());

        return response()->json(['user' => Present::user($user)]);
    }

    /**
     * Validates the format of present, non-blank fields.
     *
     * @return array<string, list<string>>
     */
    private function formatErrors(array $data): array
    {
        $errors = [];
        $email = $data['email'] ?? null;
        if (is_string($email) && trim($email) !== '' && filter_var(trim($email), FILTER_VALIDATE_EMAIL) === false) {
            $errors['email'] = ['is invalid'];
        }
        $password = $data['password'] ?? null;
        if (is_string($password) && $password !== '' && mb_strlen($password) < self::MIN_PASSWORD_LENGTH) {
            $errors['password'] = ['is too short (minimum is '.self::MIN_PASSWORD_LENGTH.' characters)'];
        }

        return $errors;
    }

    /**
     * @param  array{username: string, email: string}  $attributes
     */
    private function assertUnique(array $attributes, ?int $exceptId = null): void
    {
        $errors = [];
        foreach ($attributes as $field => $value) {
            $exists = User::where($field, $value)
                ->when($exceptId !== null, fn ($query) => $query->where('id', '!=', $exceptId))
                ->exists();
            if ($exists) {
                $errors[$field] = ['has already been taken'];
            }
        }
        if ($errors !== []) {
            throw new ApiError(409, $errors);
        }
    }

    /**
     * Runs a write and maps a concurrent unique-constraint race to a 409 response.
     */
    private function saveUnique(callable $write): mixed
    {
        try {
            return $write();
        } catch (UniqueConstraintViolationException $e) {
            $field = str_contains($e->getMessage(), 'users.email') ? 'email' : 'username';
            throw ApiError::make(409, $field, 'has already been taken');
        }
    }
}
