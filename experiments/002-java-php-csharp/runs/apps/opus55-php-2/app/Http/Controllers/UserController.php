<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\ApiException;
use App\Support\Input;
use App\Support\Jwt;
use App\Support\Presenter;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;

class UserController
{
    private const MIN_PASSWORD_LENGTH = 8;

    public function register(Request $request): JsonResponse
    {
        $input = Input::from($request, 'user');
        $username = $input->required('username');
        $email = $input->required('email');
        $password = $input->required('password');
        $input->email('email', $email);
        $input->minLength('password', $password, self::MIN_PASSWORD_LENGTH);
        $input->validate();

        $this->ensureUnique($username, $email, null);

        try {
            $user = User::create([
                'username' => $username,
                'email' => $email,
                'password' => Hash::make($password),
            ]);
        } catch (UniqueConstraintViolationException) {
            $this->ensureUnique($username, $email, null);
            throw ApiException::make(409, 'username', 'has already been taken');
        }

        return new JsonResponse(Presenter::user($user, Jwt::issue($user)), 201);
    }

    public function login(Request $request): JsonResponse
    {
        $input = Input::from($request, 'user');
        $email = $input->required('email');
        $password = $input->required('password');
        $input->validate();

        $user = User::where('email', $email)->first();
        if ($user === null || ! Hash::check($password, $user->password)) {
            throw ApiException::make(401, 'credentials', 'invalid');
        }

        return new JsonResponse(Presenter::user($user, Jwt::issue($user)));
    }

    public function current(Request $request): JsonResponse
    {
        return new JsonResponse(Presenter::user($request->attributes->get('user'), $request->attributes->get('token')));
    }

    public function update(Request $request): JsonResponse
    {
        /** @var User $user */
        $user = $request->attributes->get('user');
        $input = Input::from($request, 'user');

        $changes = [];
        if ($input->has('username')) {
            $changes['username'] = $input->required('username');
        }
        if ($input->has('email')) {
            $changes['email'] = $input->required('email');
            $input->email('email', $changes['email']);
        }
        if ($input->has('password')) {
            $password = $input->required('password');
            $input->minLength('password', $password, self::MIN_PASSWORD_LENGTH);
            $changes['password'] = $password;
        }
        foreach (['bio', 'image'] as $field) {
            if ($input->has($field)) {
                $changes[$field] = $input->nullable($field);
            }
        }
        $input->validate();

        $this->ensureUnique($changes['username'] ?? null, $changes['email'] ?? null, $user->id);

        if (isset($changes['password'])) {
            $changes['password'] = Hash::make($changes['password']);
        }

        try {
            $user->fill($changes)->save();
        } catch (UniqueConstraintViolationException) {
            throw ApiException::make(409, 'username', 'has already been taken');
        }

        return new JsonResponse(Presenter::user($user, Jwt::issue($user)));
    }

    /** Throws 409 when the username or email belongs to another user. */
    private function ensureUnique(?string $username, ?string $email, ?int $exceptId): void
    {
        $errors = [];
        foreach (['username' => $username, 'email' => $email] as $field => $value) {
            if ($value === null) {
                continue;
            }
            $query = User::where($field, $value);
            if ($exceptId !== null) {
                $query->whereKeyNot($exceptId);
            }
            if ($query->exists()) {
                $errors[$field] = ['has already been taken'];
            }
        }

        if ($errors !== []) {
            throw new ApiException(409, $errors);
        }
    }
}
