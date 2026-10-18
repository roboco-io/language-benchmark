#pragma once
#include <drogon/HttpRequest.h>
#include <json/json.h>

#include <cstdint>
#include <optional>
#include <string>

#include "util.h"

void registerUserRoutes();
void registerArticleRoutes();

// Returns the object under body[key], or sends 422 and returns nullopt.
std::optional<Json::Value> requireWrapped(const drogon::HttpRequestPtr &req, const Callback &cb,
                                          const char *key);

// Profile JSON for the given user as seen by viewerId (0 = anonymous).
std::optional<Json::Value> loadProfile(int64_t userId, int64_t viewerId);
