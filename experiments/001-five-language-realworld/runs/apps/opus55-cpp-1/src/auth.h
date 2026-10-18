#pragma once

#include "common.h"

// Password hashing with PBKDF2-HMAC-SHA256 and a random salt.
std::string hashPassword(const std::string &password);
bool verifyPassword(const std::string &password, const std::string &stored);

// HS256 JWT whose "sub" claim is the user id.
std::string makeToken(int64_t userId);
std::optional<int64_t> verifyToken(const std::string &token);

// Result of reading the Authorization header.
struct AuthResult
{
    std::optional<int64_t> userId;  // set when a valid token is present
    HttpResponsePtr error;          // set when the request must be rejected
};

// required=true: a missing token yields a 401 error.
// required=false: a missing token yields no user and no error.
// In both modes an invalid token yields a 401 error.
AuthResult authenticate(const HttpRequestPtr &req, bool required);
