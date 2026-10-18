#include "auth.h"
#include "routes.h"
#include "util.h"

#include <set>

using namespace drogon;

namespace rw
{
namespace
{
// Selects every column needed to render an article for viewer `?` (0 = none).
// The first two bound parameters are the viewer id (favorited, following).
const std::string kArticleSelect = R"SQL(
SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at,
       a.updated_at, a.author_id, u.username, u.bio, u.image,
       (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id)
           AS favorites_count,
       EXISTS(SELECT 1 FROM favorites f
              WHERE f.article_id = a.id AND f.user_id = ?) AS favorited,
       EXISTS(SELECT 1 FROM follows w
              WHERE w.followee_id = a.author_id AND w.follower_id = ?)
           AS following,
       (SELECT json_group_array(name) FROM
           (SELECT t.name AS name FROM article_tags at
            JOIN tags t ON t.id = at.tag_id
            WHERE at.article_id = a.id ORDER BY at.position)) AS tag_list
FROM articles a JOIN users u ON u.id = a.author_id
)SQL";

constexpr int64_t kDefaultLimit = 20;
constexpr int64_t kMaxLimit = 100;

Json::Value articleJson(const orm::Row &row, bool includeBody)
{
    Json::Value a;
    a["slug"] = row["slug"].as<std::string>();
    a["title"] = row["title"].as<std::string>();
    a["description"] = row["description"].as<std::string>();
    if (includeBody)
        a["body"] = row["body"].as<std::string>();

    Json::Value tags(Json::arrayValue);
    std::string raw = row["tag_list"].isNull()
                          ? std::string("[]")
                          : row["tag_list"].as<std::string>();
    Json::CharReaderBuilder builder;
    std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
    std::string errs;
    Json::Value parsed;
    if (reader->parse(raw.data(), raw.data() + raw.size(), &parsed, &errs) &&
        parsed.isArray())
        tags = parsed;
    a["tagList"] = tags;

    a["createdAt"] = row["created_at"].as<std::string>();
    a["updatedAt"] = row["updated_at"].as<std::string>();
    a["favorited"] = row["favorited"].as<int64_t>() != 0;
    a["favoritesCount"] = Json::Int64(row["favorites_count"].as<int64_t>());

    Json::Value author;
    author["username"] = row["username"].as<std::string>();
    author["bio"] = nullableString(row["bio"]);
    author["image"] = nullableString(row["image"]);
    author["following"] = row["following"].as<int64_t>() != 0;
    a["author"] = author;
    return a;
}

Task<std::optional<orm::Row>> loadArticle(const std::string &slug,
                                          int64_t viewerId)
{
    auto r = co_await db()->execSqlCoro(kArticleSelect + " WHERE a.slug = ?",
                                        viewerId,
                                        viewerId,
                                        slug);
    if (r.empty())
        co_return std::nullopt;
    co_return r[0];
}

Task<HttpResponsePtr> singleArticleResponse(const std::string &slug,
                                            int64_t viewerId,
                                            HttpStatusCode code = k200OK)
{
    auto row = co_await loadArticle(slug, viewerId);
    if (!row)
        co_return errorResponse(k404NotFound, "article", "not found");
    Json::Value root;
    root["article"] = articleJson(*row, true);
    co_return jsonResponse(root, code);
}

// Validates tagList: must be an array of strings. Returns deduplicated tags
// in their original order, or std::nullopt when invalid.
std::optional<std::vector<std::string>> parseTags(const Json::Value &v)
{
    if (!v.isArray())
        return std::nullopt;
    std::vector<std::string> tags;
    std::set<std::string> seen;
    for (const auto &t : v)
    {
        if (!t.isString())
            return std::nullopt;
        std::string name = t.asString();
        if (name.empty())
            continue;
        if (seen.insert(name).second)
            tags.push_back(name);
    }
    return tags;
}

Task<void> replaceTags(const std::shared_ptr<orm::Transaction> &tx,
                       int64_t articleId,
                       const std::vector<std::string> &tags)
{
    co_await tx->execSqlCoro("DELETE FROM article_tags WHERE article_id = ?",
                             articleId);
    int64_t position = 0;
    for (const auto &name : tags)
    {
        co_await tx->execSqlCoro("INSERT OR IGNORE INTO tags (name) VALUES (?)",
                                 name);
        auto r = co_await tx->execSqlCoro("SELECT id FROM tags WHERE name = ?",
                                          name);
        co_await tx->execSqlCoro(
            "INSERT INTO article_tags (article_id, tag_id, position) "
            "VALUES (?, ?, ?)",
            articleId,
            r[0]["id"].as<int64_t>(),
            position++);
    }
}

std::optional<int64_t> intParam(const HttpRequestPtr &req,
                                const std::string &name)
{
    const std::string &v = req->getParameter(name);
    if (v.empty())
        return std::nullopt;
    try
    {
        size_t used = 0;
        long long n = std::stoll(v, &used);
        if (used != v.size())
            return std::nullopt;
        return n;
    }
    catch (...)
    {
        return std::nullopt;
    }
}

Task<HttpResponsePtr> listArticles(HttpRequestPtr req, bool feed)
{
    // Note: co_await inside ?: is miscompiled by some GCC versions, so the
    // two cases use separate statements.
    AuthResult auth;
    if (feed)
        auth = co_await requireAuth(req);
    else
        auth = co_await optionalAuth(req);
    if (auth.error)
        co_return auth.error;
    int64_t viewerId = auth.user ? auth.user->id : 0;

    int64_t limit = intParam(req, "limit").value_or(kDefaultLimit);
    int64_t offset = intParam(req, "offset").value_or(0);
    limit = std::clamp<int64_t>(limit, 0, kMaxLimit);
    offset = std::max<int64_t>(offset, 0);

    std::string tag = feed ? "" : req->getParameter("tag");
    std::string author = feed ? "" : req->getParameter("author");
    std::string favorited = feed ? "" : req->getParameter("favorited");

    // Each optional filter is disabled by binding an empty string.
    const std::string where = R"SQL(
WHERE (? = '' OR EXISTS(SELECT 1 FROM article_tags at JOIN tags t
                        ON t.id = at.tag_id
                        WHERE at.article_id = a.id AND t.name = ?))
  AND (? = '' OR u.username = ?)
  AND (? = '' OR EXISTS(SELECT 1 FROM favorites f JOIN users fu
                        ON fu.id = f.user_id
                        WHERE f.article_id = a.id AND fu.username = ?))
  AND (? = 0 OR EXISTS(SELECT 1 FROM follows w
                       WHERE w.follower_id = ? AND w.followee_id = a.author_id))
)SQL";
    int64_t feedFlag = feed ? 1 : 0;

    auto rows = co_await db()->execSqlCoro(
        kArticleSelect + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
        viewerId,
        viewerId,
        tag,
        tag,
        author,
        author,
        favorited,
        favorited,
        feedFlag,
        viewerId,
        limit,
        offset);
    auto count = co_await db()->execSqlCoro(
        "SELECT COUNT(*) AS n FROM articles a JOIN users u ON u.id = a.author_id" +
            where,
        tag,
        tag,
        author,
        author,
        favorited,
        favorited,
        feedFlag,
        viewerId);

    Json::Value root;
    root["articles"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows)
        root["articles"].append(articleJson(row, false));
    root["articlesCount"] = Json::Int64(count[0]["n"].as<int64_t>());
    co_return jsonResponse(root);
}

// Checks that each listed field, when present, is a non-empty string.
HttpResponsePtr requireNonBlank(const Json::Value &in,
                                std::initializer_list<const char *> fields,
                                bool mustExist)
{
    for (const char *name : fields)
    {
        if (!mustExist && !in.isMember(name))
            continue;
        const Json::Value &v = in[name];
        if (!v.isString() || v.asString().empty())
            return errorResponse(k422UnprocessableEntity, name, "can't be blank");
    }
    return nullptr;
}

const Json::Value *wrappedArticle(const std::optional<Json::Value> &body)
{
    if (!body || !body->isObject() || !(*body)["article"].isObject())
        return nullptr;
    return &(*body)["article"];
}

Task<HttpResponsePtr> createArticle(HttpRequestPtr req)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto body = parseBody(req);
    const Json::Value *in = wrappedArticle(body);
    if (!in)
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    if (auto err = requireNonBlank(*in, {"title", "description", "body"}, true))
        co_return err;
    std::vector<std::string> tags;
    if (in->isMember("tagList"))
    {
        auto parsed = parseTags((*in)["tagList"]);
        if (!parsed)
            co_return errorResponse(k422UnprocessableEntity,
                                    "tagList",
                                    "must be an array of strings");
        tags = std::move(*parsed);
    }

    std::string slug = makeSlug((*in)["title"].asString());
    std::string now = nowIso8601();
    {
        auto tx = co_await db()->newTransactionCoro();
        try
        {
        auto r = co_await tx->execSqlCoro(
            "INSERT INTO articles (slug, title, description, body, author_id, "
            "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            slug,
            (*in)["title"].asString(),
            (*in)["description"].asString(),
            (*in)["body"].asString(),
            auth.user->id,
            now,
            now);
        co_await replaceTags(tx, static_cast<int64_t>(r.insertId()), tags);
        }
        catch (...)
        {
            tx->rollback();
            throw;
        }
        if (!co_await CommitAwaiter(std::move(tx)))
            throw std::runtime_error("commit failed");
    }
    co_return co_await singleArticleResponse(slug, auth.user->id, k201Created);
}

Task<HttpResponsePtr> getArticle(HttpRequestPtr req, std::string slug)
{
    if (slug == "feed")
        co_return co_await listArticles(req, true);
    auto auth = co_await optionalAuth(req);
    if (auth.error)
        co_return auth.error;
    co_return co_await singleArticleResponse(slug,
                                             auth.user ? auth.user->id : 0);
}

// Loads (id, author_id) for a slug, or std::nullopt when not found.
Task<std::optional<std::pair<int64_t, int64_t>>> findArticle(
    const std::string &slug)
{
    auto r = co_await db()->execSqlCoro(
        "SELECT id, author_id FROM articles WHERE slug = ?", slug);
    if (r.empty())
        co_return std::nullopt;
    co_return std::make_pair(r[0]["id"].as<int64_t>(),
                             r[0]["author_id"].as<int64_t>());
}

Task<HttpResponsePtr> updateArticle(HttpRequestPtr req, std::string slug)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto found = co_await findArticle(slug);
    if (!found)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (found->second != auth.user->id)
        co_return errorResponse(k403Forbidden, "article", "forbidden");

    auto body = parseBody(req);
    const Json::Value *in = wrappedArticle(body);
    if (!in)
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    if (auto err = requireNonBlank(*in, {"title", "description", "body"}, false))
        co_return err;
    std::optional<std::vector<std::string>> tags;
    if (in->isMember("tagList"))
    {
        tags = parseTags((*in)["tagList"]);
        if (!tags)
            co_return errorResponse(k422UnprocessableEntity,
                                    "tagList",
                                    "must be an array of strings");
    }

    std::string newSlug = slug;
    {
        auto tx = co_await db()->newTransactionCoro();
        try
        {
        auto cur = co_await tx->execSqlCoro(
            "SELECT title, description, body FROM articles WHERE id = ?",
            found->first);
        std::string title = cur[0]["title"].as<std::string>();
        std::string description = cur[0]["description"].as<std::string>();
        std::string text = cur[0]["body"].as<std::string>();
        if (in->isMember("title") && (*in)["title"].asString() != title)
        {
            title = (*in)["title"].asString();
            newSlug = makeSlug(title);
        }
        if (in->isMember("description"))
            description = (*in)["description"].asString();
        if (in->isMember("body"))
            text = (*in)["body"].asString();
        co_await tx->execSqlCoro(
            "UPDATE articles SET slug = ?, title = ?, description = ?, body = ?, "
            "updated_at = ? WHERE id = ?",
            newSlug,
            title,
            description,
            text,
            nowIso8601(),
            found->first);
        if (tags)
            co_await replaceTags(tx, found->first, *tags);
        }
        catch (...)
        {
            tx->rollback();
            throw;
        }
        if (!co_await CommitAwaiter(std::move(tx)))
            throw std::runtime_error("commit failed");
    }
    co_return co_await singleArticleResponse(newSlug, auth.user->id);
}

Task<HttpResponsePtr> deleteArticle(HttpRequestPtr req, std::string slug)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto found = co_await findArticle(slug);
    if (!found)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (found->second != auth.user->id)
        co_return errorResponse(k403Forbidden, "article", "forbidden");
    co_await db()->execSqlCoro("DELETE FROM articles WHERE id = ?", found->first);
    auto resp = HttpResponse::newHttpResponse();
    resp->setStatusCode(k204NoContent);
    co_return resp;
}

Task<HttpResponsePtr> setFavorite(HttpRequestPtr req,
                                  std::string slug,
                                  bool favorite)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto found = co_await findArticle(slug);
    if (!found)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (favorite)
        co_await db()->execSqlCoro(
            "INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)",
            auth.user->id,
            found->first);
    else
        co_await db()->execSqlCoro(
            "DELETE FROM favorites WHERE user_id = ? AND article_id = ?",
            auth.user->id,
            found->first);
    co_return co_await singleArticleResponse(slug, auth.user->id);
}

const std::string kCommentSelect = R"SQL(
SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,
       EXISTS(SELECT 1 FROM follows w
              WHERE w.followee_id = c.author_id AND w.follower_id = ?)
           AS following
FROM comments c JOIN users u ON u.id = c.author_id
)SQL";

Json::Value commentJson(const orm::Row &row)
{
    Json::Value c;
    c["id"] = Json::Int64(row["id"].as<int64_t>());
    c["body"] = row["body"].as<std::string>();
    c["createdAt"] = row["created_at"].as<std::string>();
    c["updatedAt"] = row["updated_at"].as<std::string>();
    Json::Value author;
    author["username"] = row["username"].as<std::string>();
    author["bio"] = nullableString(row["bio"]);
    author["image"] = nullableString(row["image"]);
    author["following"] = row["following"].as<int64_t>() != 0;
    c["author"] = author;
    return c;
}

Task<HttpResponsePtr> listComments(HttpRequestPtr req, std::string slug)
{
    auto auth = co_await optionalAuth(req);
    if (auth.error)
        co_return auth.error;
    auto found = co_await findArticle(slug);
    if (!found)
        co_return errorResponse(k404NotFound, "article", "not found");
    auto rows = co_await db()->execSqlCoro(
        kCommentSelect + " WHERE c.article_id = ? ORDER BY c.id ASC",
        auth.user ? auth.user->id : 0,
        found->first);
    Json::Value root;
    root["comments"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows)
        root["comments"].append(commentJson(row));
    co_return jsonResponse(root);
}

Task<HttpResponsePtr> createComment(HttpRequestPtr req, std::string slug)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto found = co_await findArticle(slug);
    if (!found)
        co_return errorResponse(k404NotFound, "article", "not found");
    auto body = parseBody(req);
    if (!body || !body->isObject() || !(*body)["comment"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["comment"];
    if (auto err = requireNonBlank(in, {"body"}, true))
        co_return err;
    std::string now = nowIso8601();
    auto r = co_await db()->execSqlCoro(
        "INSERT INTO comments (body, article_id, author_id, created_at, "
        "updated_at) VALUES (?, ?, ?, ?, ?)",
        in["body"].asString(),
        found->first,
        auth.user->id,
        now,
        now);
    auto rows = co_await db()->execSqlCoro(kCommentSelect + " WHERE c.id = ?",
                                           auth.user->id,
                                           static_cast<int64_t>(r.insertId()));
    Json::Value root;
    root["comment"] = commentJson(rows[0]);
    co_return jsonResponse(root, k201Created);
}

Task<HttpResponsePtr> deleteComment(HttpRequestPtr req,
                                    std::string slug,
                                    std::string idText)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto found = co_await findArticle(slug);
    if (!found)
        co_return errorResponse(k404NotFound, "article", "not found");
    int64_t id = 0;
    try
    {
        id = std::stoll(idText);
    }
    catch (...)
    {
        co_return errorResponse(k404NotFound, "comment", "not found");
    }
    auto r = co_await db()->execSqlCoro(
        "SELECT author_id FROM comments WHERE id = ? AND article_id = ?",
        id,
        found->first);
    if (r.empty())
        co_return errorResponse(k404NotFound, "comment", "not found");
    if (r[0]["author_id"].as<int64_t>() != auth.user->id)
        co_return errorResponse(k403Forbidden, "comment", "forbidden");
    co_await db()->execSqlCoro("DELETE FROM comments WHERE id = ?", id);
    auto resp = HttpResponse::newHttpResponse();
    resp->setStatusCode(k204NoContent);
    co_return resp;
}

Task<HttpResponsePtr> listTags(HttpRequestPtr)
{
    auto rows = co_await db()->execSqlCoro(
        "SELECT t.name FROM tags t WHERE EXISTS "
        "(SELECT 1 FROM article_tags at WHERE at.tag_id = t.id) "
        "ORDER BY t.name");
    Json::Value root;
    root["tags"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows)
        root["tags"].append(row["name"].as<std::string>());
    co_return jsonResponse(root);
}
}  // namespace

void registerArticleRoutes()
{
    app().registerHandler(
        "/api/articles",
        [](HttpRequestPtr req) -> Task<HttpResponsePtr> {
            co_return co_await listArticles(req, false);
        },
        {Get});
    app().registerHandler("/api/articles", &createArticle, {Post});
    // GET /api/articles/feed is dispatched inside getArticle.
    app().registerHandler("/api/articles/{slug}", &getArticle, {Get});
    app().registerHandler("/api/articles/{slug}", &updateArticle, {Put});
    app().registerHandler("/api/articles/{slug}", &deleteArticle, {Delete});
    app().registerHandler(
        "/api/articles/{slug}/favorite",
        [](HttpRequestPtr req, std::string slug) -> Task<HttpResponsePtr> {
            co_return co_await setFavorite(req, std::move(slug), true);
        },
        {Post});
    app().registerHandler(
        "/api/articles/{slug}/favorite",
        [](HttpRequestPtr req, std::string slug) -> Task<HttpResponsePtr> {
            co_return co_await setFavorite(req, std::move(slug), false);
        },
        {Delete});
    app().registerHandler("/api/articles/{slug}/comments", &listComments, {Get});
    app().registerHandler("/api/articles/{slug}/comments", &createComment, {Post});
    app().registerHandler("/api/articles/{slug}/comments/{id}",
                          &deleteComment,
                          {Delete});
    app().registerHandler("/api/tags", &listTags, {Get});
}
}  // namespace rw
