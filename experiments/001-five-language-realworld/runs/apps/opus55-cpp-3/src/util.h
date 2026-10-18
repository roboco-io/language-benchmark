#pragma once
#include <drogon/HttpRequest.h>
#include <drogon/HttpResponse.h>
#include <drogon/orm/Field.h>
#include <json/json.h>

#include <cstdint>
#include <functional>
#include <optional>
#include <string>

using Callback = std::function<void(const drogon::HttpResponsePtr &)>;

// Sends a JSON body with the given status code.
void sendJson(const Callback &cb, const Json::Value &body,
              drogon::HttpStatusCode code = drogon::k200OK);

// Sends {"errors": {field: [message]}}.
void sendError(const Callback &cb, drogon::HttpStatusCode code, const std::string &field,
               const std::string &message);

void sendNoContent(const Callback &cb);

// Result of reading the Authorization header.
struct AuthResult {
  enum class State { Missing, Invalid, Ok } state = State::Missing;
  int64_t userId = 0;
};
AuthResult authenticate(const drogon::HttpRequestPtr &req);

// Resolves the authenticated user or sends a 401 and returns nullopt.
std::optional<int64_t> requireUser(const drogon::HttpRequestPtr &req, const Callback &cb);

// Resolves the optional viewer: 0 when anonymous. Sends 401 for an invalid
// token and returns nullopt.
std::optional<int64_t> optionalUser(const drogon::HttpRequestPtr &req, const Callback &cb);

// Current UTC time as ISO-8601 with millisecond precision.
std::string nowIso();

std::string slugify(const std::string &title);
std::string randomSuffix();

// Null JSON for SQL NULL / empty strings.
Json::Value nullableString(const drogon::orm::Field &field);
