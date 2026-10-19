<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Http\Presenter;
use App\Models\User;
use App\Support\Jwt;
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
        $errors = [];
        $username = $this->requireString($data, 'username', $errors);
        $email = $this->requireString($data, 'email', $errors);
        $password = $this->requireString($data, 'password', $errors);
        $this->failIfErrors($errors);

        $this->ensureUnique($username, $email);

        try {
            $user = User::create(['username' => $username, 'email' => $email, 'password' => $password]);
        } catch (UniqueConstraintViolationException) {
            // Another request registered the same name between the check and the insert.
            $this->ensureUnique($username, $email);
            throw ApiException::field(409, 'username', 'has already been taken');
        }

        return response()->json(['user' => Presenter::user($user, Jwt::issue($user->id))], 201);
    }

    public function login(Request $request): JsonResponse
    {
        $data = $this->payload($request, 'user');
        $errors = [];
        $email = $this->requireString($data, 'email', $errors);
        $password = $this->requireString($data, 'password', $errors);
        $this->failIfErrors($errors);

        $user = User::where('email', $email)->first();

        if ($user === null || ! Hash::check($password, $user->password)) {
            throw ApiException::field(401, 'credentials', 'invalid');
        }

        return response()->json(['user' => Presenter::user($user, Jwt::issue($user->id))]);
    }

    public function show(Request $request): JsonResponse
    {
        $user = $this->viewer($request);

        return response()->json(['user' => Presenter::user($user, Jwt::issue($user->id))]);
    }

    public function update(Request $request): JsonResponse
    {
        $user = $this->viewer($request);
        $data = $this->payload($request, 'user');
        $errors = [];
        $changes = [];

        foreach (['username', 'email'] as $field) {
            if (array_key_exists($field, $data)) {
                $changes[$field] = $this->requireString($data, $field, $errors);
            }
        }

        if (array_key_exists('password', $data)) {
            $password = $data['password'];
            if (! is_string($password) || $password === '') {
                $errors['password'][] = "can't be blank";
            } elseif (mb_strlen($password) < self::MIN_PASSWORD_LENGTH) {
                $errors['password'][] = 'is too short (minimum is '.self::MIN_PASSWORD_LENGTH.' characters)';
            } else {
                $changes['password'] = $password;
            }
        }

        // bio and image are nullable: an empty string is stored as null.
        foreach (['bio', 'image'] as $field) {
            if (array_key_exists($field, $data)) {
                $value = $data[$field];
                if ($value !== null && ! is_string($value)) {
                    $errors[$field][] = 'is invalid';
                } else {
                    $changes[$field] = $value === '' ? null : $value;
                }
            }
        }

        $this->failIfErrors($errors);

        $this->ensureUnique(
            isset($changes['username']) && $changes['username'] !== $user->username ? $changes['username'] : null,
            isset($changes['email']) && $changes['email'] !== $user->email ? $changes['email'] : null,
        );

        $user->fill($changes);

        try {
            $user->save();
        } catch (UniqueConstraintViolationException) {
            throw ApiException::field(409, 'username', 'has already been taken');
        }

        return response()->json(['user' => Presenter::user($user, Jwt::issue($user->id))]);
    }

    /**
     * Throws 409 when the username or email already belongs to a user.
     */
    private function ensureUnique(?string $username, ?string $email): void
    {
        $errors = [];

        if ($username !== null && User::where('username', $username)->exists()) {
            $errors['username'][] = 'has already been taken';
        }

        if ($email !== null && User::where('email', $email)->exists()) {
            $errors['email'][] = 'has already been taken';
        }

        if ($errors !== []) {
            throw new ApiException(409, $errors);
        }
    }
}
