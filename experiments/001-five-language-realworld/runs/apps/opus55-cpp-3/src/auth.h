#pragma once
#include <cstdint>
#include <optional>
#include <string>

namespace auth {

void setSecret(const std::string &secret);

// HS256 JWT carrying the user id in "sub".
std::string makeToken(int64_t userId);
std::optional<int64_t> verifyToken(const std::string &token);

// PBKDF2-HMAC-SHA256 with a random salt, encoded as "iterations$salt$hash".
std::string hashPassword(const std::string &password);
bool checkPassword(const std::string &password, const std::string &stored);

}  // namespace auth
