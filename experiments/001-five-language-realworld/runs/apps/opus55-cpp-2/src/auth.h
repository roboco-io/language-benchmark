#pragma once

#include <drogon/drogon.h>
#include <drogon/utils/coroutine.h>

#include <cstdint>
#include <optional>
#include <string>

namespace rw
{
// Authenticated user loaded from the database.
struct User
{
    int64_t id{0};
    std::string username;
    std::string email;
    std::optional<std::string> bio;
    std::optional<std::string> image;
};

// Result of reading the Authorization header.
struct AuthResult
{
    std::optional<User> user;          // Set when a valid token was given.
    drogon::HttpResponsePtr error;     // Set when the token is invalid/missing.
};

void initJwtSecret();
std::string createToken(const User &user);

std::string hashPassword(const std::string &password);
bool verifyPassword(const std::string &password, const std::string &stored);

// Requires a valid token. On failure `error` holds a 401 response.
drogon::Task<AuthResult> requireAuth(const drogon::HttpRequestPtr &req);

// Accepts requests without a token. An invalid token still yields a 401.
drogon::Task<AuthResult> optionalAuth(const drogon::HttpRequestPtr &req);

// Loads a user by id. Returns std::nullopt when the user does not exist.
drogon::Task<std::optional<User>> loadUserById(int64_t id);
}  // namespace rw
