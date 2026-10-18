#pragma once

#include <drogon/drogon.h>
#include <json/json.h>

#include <cstdint>
#include <memory>
#include <optional>
#include <string>

using drogon::HttpRequestPtr;
using drogon::HttpResponsePtr;
using drogon::Task;

// Shared database client, created in main() before the server starts.
drogon::orm::DbClientPtr db();
void setDb(drogon::orm::DbClientPtr client);

// Current time in milliseconds since the Unix epoch.
int64_t nowMillis();
// Formats epoch milliseconds as ISO 8601 UTC, e.g. 2024-01-01T00:00:00.000Z.
std::string isoTime(int64_t millis);

// Parses the request body as JSON; returns nullptr if it is not valid JSON.
std::shared_ptr<Json::Value> parseBody(const HttpRequestPtr &req);

HttpResponsePtr jsonResponse(const Json::Value &body,
                             drogon::HttpStatusCode code = drogon::k200OK);
HttpResponsePtr errorResponse(drogon::HttpStatusCode code,
                              const std::string &field,
                              const std::string &message);
// Builds a 422 response from an {"field": ["msg", ...]} object.
HttpResponsePtr validationResponse(const Json::Value &errors);
HttpResponsePtr noContent();

// Returns true if the value is a non-empty string.
bool isNonEmptyString(const Json::Value &v);

std::string slugify(const std::string &title);
std::string randomHex(size_t bytes);

// Converts a nullable text column to JSON (null or string).
Json::Value nullableText(const drogon::orm::Field &f);

// Route registration, one function per resource.
void registerUserRoutes();
void registerProfileRoutes();
void registerArticleRoutes();
void registerCommentRoutes();
