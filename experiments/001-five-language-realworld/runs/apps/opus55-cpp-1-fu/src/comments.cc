#include "articles.h"
#include "auth.h"

#include <algorithm>

using namespace drogon;

namespace
{
const std::string kCommentSelect = R"(
SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,
  EXISTS(SELECT 1 FROM follows fo WHERE fo.follower_id = ? AND fo.followee_id = c.author_id) AS following
FROM comments c JOIN users u ON u.id = c.author_id
)";

Json::Value commentJson(const orm::Row &row)
{
    Json::Value c;
    c["id"] = row["id"].as<int64_t>();
    c["createdAt"] = isoTime(row["created_at"].as<int64_t>());
    c["updatedAt"] = isoTime(row["updated_at"].as<int64_t>());
    c["body"] = row["body"].as<std::string>();
    c["author"] = authorJson(row, "username", "bio", "image", "following");
    return c;
}

Task<HttpResponsePtr> listComments(HttpRequestPtr req, std::string slug)
{
    auto auth = authenticate(req, false);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    auto rows = co_await db()->execSqlCoro(
        kCommentSelect + " WHERE c.article_id = ? ORDER BY c.id",
        auth.userId.value_or(0),
        ref->id);
    Json::Value body;
    body["comments"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows)
        body["comments"].append(commentJson(row));
    co_return jsonResponse(body);
}

Task<HttpResponsePtr> addComment(HttpRequestPtr req, std::string slug)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    auto body = parseBody(req);
    if (!body || !(*body)["comment"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["comment"];
    if (!isNonEmptyString(in["body"]))
        co_return errorResponse(k422UnprocessableEntity, "body", "can't be blank");

    int64_t now = nowMillis();
    auto result = co_await db()->execSqlCoro(
        "INSERT INTO comments (body, article_id, author_id, created_at, updated_at) "
        "VALUES (?, ?, ?, ?, ?)",
        in["body"].asString(),
        ref->id,
        *auth.userId,
        now,
        now);
    auto rows = co_await db()->execSqlCoro(kCommentSelect + " WHERE c.id = ?",
                                           *auth.userId,
                                           static_cast<int64_t>(result.insertId()));
    Json::Value out;
    out["comment"] = commentJson(rows[0]);
    co_return jsonResponse(out, k201Created);
}

Task<HttpResponsePtr> deleteComment(HttpRequestPtr req, std::string slug, std::string id)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    int64_t commentId = 0;
    try
    {
        commentId = std::stoll(id);
    }
    catch (...)
    {
        co_return errorResponse(k404NotFound, "comment", "not found");
    }
    auto rows = co_await db()->execSqlCoro(
        "SELECT author_id FROM comments WHERE id = ? AND article_id = ?",
        commentId,
        ref->id);
    if (rows.empty())
        co_return errorResponse(k404NotFound, "comment", "not found");
    if (rows[0]["author_id"].as<int64_t>() != *auth.userId)
        co_return errorResponse(k403Forbidden, "comment", "forbidden");
    co_await db()->execSqlCoro("DELETE FROM comments WHERE id = ?", commentId);
    co_return noContent();
}

Task<HttpResponsePtr> updateComment(HttpRequestPtr req, std::string slug, std::string id)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    int64_t commentId = 0;
    try
    {
        commentId = std::stoll(id);
    }
    catch (...)
    {
        co_return errorResponse(k404NotFound, "comment", "not found");
    }
    auto rows = co_await db()->execSqlCoro(
        "SELECT author_id, updated_at FROM comments WHERE id = ? AND article_id = ?",
        commentId,
        ref->id);
    if (rows.empty())
        co_return errorResponse(k404NotFound, "comment", "not found");
    if (rows[0]["author_id"].as<int64_t>() != *auth.userId)
        co_return errorResponse(k403Forbidden, "comment", "forbidden");
    auto body = parseBody(req);
    if (!body || !(*body)["comment"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["comment"];
    if (!isNonEmptyString(in["body"]))
        co_return errorResponse(k422UnprocessableEntity, "body", "can't be blank");

    // Keep updatedAt strictly increasing even within the same millisecond.
    int64_t now = std::max(nowMillis(), rows[0]["updated_at"].as<int64_t>() + 1);
    co_await db()->execSqlCoro("UPDATE comments SET body = ?, updated_at = ? WHERE id = ?",
                               in["body"].asString(),
                               now,
                               commentId);
    auto updated =
        co_await db()->execSqlCoro(kCommentSelect + " WHERE c.id = ?", *auth.userId, commentId);
    Json::Value out;
    out["comment"] = commentJson(updated[0]);
    co_return jsonResponse(out);
}
}  // namespace

void registerCommentRoutes()
{
    app().registerHandler("/api/articles/{slug}/comments", &listComments, {Get});
    app().registerHandler("/api/articles/{slug}/comments", &addComment, {Post});
    app().registerHandler("/api/articles/{slug}/comments/{id}", &updateComment, {Put});
    app().registerHandler("/api/articles/{slug}/comments/{id}", &deleteComment, {Delete});
}
