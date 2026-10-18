#include "auth.h"

using namespace drogon;

namespace
{
Task<std::optional<orm::Row>> findProfile(const std::string &username, int64_t viewerId)
{
    auto rows = co_await db()->execSqlCoro(
        "SELECT u.id, u.username, u.bio, u.image, "
        "EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) "
        "AS following FROM users u WHERE u.username = ?",
        viewerId,
        username);
    if (rows.empty())
        co_return std::nullopt;
    co_return rows[0];
}

Json::Value profileJson(const orm::Row &row)
{
    Json::Value profile;
    profile["username"] = row["username"].as<std::string>();
    profile["bio"] = nullableText(row["bio"]);
    profile["image"] = nullableText(row["image"]);
    profile["following"] = row["following"].as<int64_t>() != 0;
    Json::Value body;
    body["profile"] = profile;
    return body;
}

Task<HttpResponsePtr> getProfile(HttpRequestPtr req, std::string username)
{
    auto auth = authenticate(req, false);
    if (auth.error)
        co_return auth.error;
    auto row = co_await findProfile(username, auth.userId.value_or(0));
    if (!row)
        co_return errorResponse(k404NotFound, "profile", "not found");
    co_return jsonResponse(profileJson(*row));
}

Task<HttpResponsePtr> setFollow(HttpRequestPtr req, std::string username, bool follow)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto target = co_await findProfile(username, *auth.userId);
    if (!target)
        co_return errorResponse(k404NotFound, "profile", "not found");
    int64_t targetId = (*target)["id"].as<int64_t>();
    if (follow)
        co_await db()->execSqlCoro(
            "INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)",
            *auth.userId,
            targetId);
    else
        co_await db()->execSqlCoro(
            "DELETE FROM follows WHERE follower_id = ? AND followee_id = ?",
            *auth.userId,
            targetId);
    auto row = co_await findProfile(username, *auth.userId);
    co_return jsonResponse(profileJson(*row));
}
}  // namespace

void registerProfileRoutes()
{
    app().registerHandler("/api/profiles/{username}", &getProfile, {Get});
    app().registerHandler(
        "/api/profiles/{username}/follow",
        [](HttpRequestPtr req, std::string username) -> Task<HttpResponsePtr> {
            co_return co_await setFollow(req, username, true);
        },
        {Post});
    app().registerHandler(
        "/api/profiles/{username}/follow",
        [](HttpRequestPtr req, std::string username) -> Task<HttpResponsePtr> {
            co_return co_await setFollow(req, username, false);
        },
        {Delete});
}
