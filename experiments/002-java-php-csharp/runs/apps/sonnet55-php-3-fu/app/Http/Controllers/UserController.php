<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Support\Presenter;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;

class UserController extends Api
{
    public function register(Request $request)
    {
        $data = $this->check((array) $request->input('user', []), [
            'username' => ['required', 'string'],
            'email' => ['required', 'string', 'email'],
            'password' => ['required', 'string', 'min:8'],
        ]);

        $taken = [];
        if (User::where('username', $data['username'])->exists()) {
            $taken['username'] = ['has already been taken'];
        }
        if (User::where('email', $data['email'])->exists()) {
            $taken['email'] = ['has already been taken'];
        }
        if ($taken) {
            return response()->json(['errors' => $taken], 409);
        }

        $user = User::create([
            'username' => $data['username'],
            'email' => $data['email'],
            'password' => Hash::make($data['password']),
        ]);

        return response()->json(Presenter::user($user), 201);
    }

    public function login(Request $request)
    {
        $data = $this->check((array) $request->input('user', []), [
            'email' => ['required', 'string'],
            'password' => ['required', 'string'],
        ]);

        $user = User::where('email', $data['email'])->first();
        if (! $user || ! Hash::check($data['password'], $user->password)) {
            return $this->error('credentials', 'invalid', 401);
        }

        return response()->json(Presenter::user($user));
    }

    public function show(Request $request)
    {
        return response()->json(Presenter::user($this->viewer($request), $request->attributes->get('auth_token')));
    }

    public function update(Request $request)
    {
        $user = $this->viewer($request);
        $input = (array) $request->input('user', []);

        $rules = [];
        foreach (['username', 'email', 'password'] as $field) {
            if (array_key_exists($field, $input)) {
                $rules[$field] = ['required', 'string'];
            }
        }
        if (isset($rules['email'])) {
            $rules['email'][] = 'email';
        }
        if (isset($rules['password'])) {
            $rules['password'][] = 'min:8';
        }
        foreach (['bio', 'image'] as $field) {
            if (array_key_exists($field, $input)) {
                $rules[$field] = ['nullable', 'string'];
            }
        }
        $data = $this->check($input, $rules);

        $taken = [];
        if (isset($data['username']) && User::where('username', $data['username'])->where('id', '!=', $user->id)->exists()) {
            $taken['username'] = ['has already been taken'];
        }
        if (isset($data['email']) && User::where('email', $data['email'])->where('id', '!=', $user->id)->exists()) {
            $taken['email'] = ['has already been taken'];
        }
        if ($taken) {
            return response()->json(['errors' => $taken], 409);
        }

        foreach (['bio', 'image'] as $field) {
            if (array_key_exists($field, $data) && $data[$field] === '') {
                $data[$field] = null;
            }
        }
        if (isset($data['password'])) {
            $data['password'] = Hash::make($data['password']);
        }

        $user->update($data);

        return response()->json(Presenter::user($user->refresh()));
    }
}
