<?php

namespace App\Http\Controllers;

use App\Exceptions\ApiException;
use App\Models\User;
use App\Support\Token;
use App\Support\Presenter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;

class UserController extends Controller
{
    public function register(Request $request): JsonResponse
    {
        $data = $this->payload($request, 'user', ['username', 'email', 'password']);
        $this->assertPassword($data['password']);
        $this->assertUnique($data['username'], $data['email']);

        $user = User::create([
            'username' => $data['username'],
            'email' => $data['email'],
            'password' => Hash::make($data['password']),
        ]);

        return response()->json(['user' => Presenter::user($user, Token::issue($user->id))], 201);
    }

    public function login(Request $request): JsonResponse
    {
        $data = $this->payload($request, 'user', ['email', 'password']);
        $user = User::where('email', $data['email'])->first();

        if (! $user || ! Hash::check($data['password'], $user->password)) {
            throw new ApiException(401, ['credentials' => ['invalid']]);
        }

        return response()->json(['user' => Presenter::user($user, Token::issue($user->id))]);
    }

    public function current(Request $request): JsonResponse
    {
        return response()->json([
            'user' => Presenter::user($this->viewer($request), $request->attributes->get('auth_token')),
        ]);
    }

    public function update(Request $request): JsonResponse
    {
        $user = $this->viewer($request);
        $data = $this->payload($request, 'user', [], ['username', 'email', 'password']);
        if (isset($data['password'])) {
            $this->assertPassword($data['password']);
        }
        $this->assertUnique($data['username'] ?? null, $data['email'] ?? null, $user->id);

        $fill = array_intersect_key($data, array_flip(['username', 'email', 'bio', 'image']));
        if (isset($data['password'])) {
            $fill['password'] = Hash::make($data['password']);
        }
        $user->fill($fill)->save();

        return response()->json(['user' => Presenter::user($user, $request->attributes->get('auth_token'))]);
    }

    private function assertPassword(string $password): void
    {
        if (mb_strlen($password) < 8) {
            throw new ApiException(422, ['password' => ['is too short (minimum is 8 characters)']]);
        }
    }

    private function assertUnique(?string $username, ?string $email, ?int $ignoreId = null): void
    {
        $errors = [];
        foreach (['username' => $username, 'email' => $email] as $field => $value) {
            if ($value !== null && User::where($field, $value)->when($ignoreId, fn ($q) => $q->where('id', '!=', $ignoreId))->exists()) {
                $errors[$field][] = 'has already been taken';
            }
        }
        if ($errors) {
            throw new ApiException(409, $errors);
        }
    }
}
