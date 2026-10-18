#include "articles.h"

#include "auth.h"

#include <set>

using namespace drogon;

namespace
{
// Tags are joined with the ASCII unit separator in one column.
constexpr char kTagSep = '\x1f';

// Selects every column needed to render an article. The two placeholders are
// the viewer id for "favorited" and for "following" (0 when anonymous).
const std::string kArticleSelect = R"(
SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at,
  u.username, u.bio, u.image,
  (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
  EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ?) AS favorited,
  EXISTS(SELECT 1 FROM follows fo WHERE fo.follower_id = ? AND fo.followee_id = a.author_id) AS following,
  (SELECT group_concat(tag, char(31)) FROM
     (SELECT t.tag FROM article_tags t WHERE t.article_id = a.id ORDER BY t.position)) AS tags
FROM articles a JOIN users u ON u.id = a.author_id
)";

// Filters shared by the list query and its count query. Each filter takes
// its value twice; an empty value (or 0 for the feed) disables the filter.
const std::string kListWhere = R"(
WHERE (? = 0 OR a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?))
  AND (? = '' OR u.username = ?)
  AND (? = '' OR EXISTS(SELECT 1 FROM article_tags t WHERE t.article_id = a.id AND t.tag = ?))
  AND (? = '' OR EXISTS(SELECT 1 FROM favorites f JOIN users fu ON fu.id = f.user_id
                        WHERE f.article_id = a.id AND fu.username = ?))
)";

Json::Value articleJson(const orm::Row &row, bool withBody)
{
    Json::Value a;
    a["slug"] = row["slug"].as<std::string>();
    a["title"] = row["title"].as<std::string>();
    a["description"] = row["description"].as<std::string>();
    if (withBody)
        a["body"] = row["body"].as<std::string>();
    Json::Value tags(Json::arrayValue);
    if (!row["tags"].isNull())
    {
        std::string joined = row["tags"].as<std::string>();
        size_t start = 0;
        while (true)
        {
            size_t end = joined.find(kTagSep, start);
            tags.append(joined.substr(start, end - start));
            if (end == std::string::npos)
                break;
            start = end + 1;
        }
    }
    a["tagList"] = tags;
    a["createdAt"] = isoTime(row["created_at"].as<int64_t>());
    a["updatedAt"] = isoTime(row["updated_at"].as<int64_t>());
    a["favorited"] = row["favorited"].as<int64_t>() != 0;
    a["favoritesCount"] = row["favorites_count"].as<int64_t>();
    a["author"] = authorJson(row, "username", "bio", "image", "following");
    return a;
}

Task<std::optional<orm::Row>> loadArticle(int64_t id, int64_t viewerId)
{
    auto rows = co_await db()->execSqlCoro(kArticleSelect + " WHERE a.id = ?",
                                           viewerId,
                                           viewerId,
                                           id);
    if (rows.empty())
        co_return std::nullopt;
    co_return rows[0];
}

Task<HttpResponsePtr> singleArticle(int64_t id,
                                    int64_t viewerId,
                                    HttpStatusCode code = k200OK)
{
    auto row = co_await loadArticle(id, viewerId);
    if (!row)
        co_return errorResponse(k404NotFound, "article", "not found");
    Json::Value body;
    body["article"] = articleJson(*row, true);
    co_return jsonResponse(body, code);
}

int64_t intParam(const HttpRequestPtr &req, const std::string &name, int64_t def)
{
    const std::string &raw = req->getParameter(name);
    if (raw.empty())
        return def;
    try
    {
        int64_t v = std::stoll(raw);
        return v < 0 ? def : v;
    }
    catch (...)
    {
        return def;
    }
}

Task<HttpResponsePtr> listArticles(HttpRequestPtr req, bool feed)
{
    auto auth = authenticate(req, feed);
    if (auth.error)
        co_return auth.error;
    int64_t viewer = auth.userId.value_or(0);
    int64_t feedFlag = feed ? 1 : 0;
    std::string author = feed ? "" : req->getParameter("author");
    std::string tag = feed ? "" : req->getParameter("tag");
    std::string favorited = feed ? "" : req->getParameter("favorited");
    int64_t limit = intParam(req, "limit", 20);
    int64_t offset = intParam(req, "offset", 0);

    auto rows = co_await db()->execSqlCoro(
        kArticleSelect + kListWhere + " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
        viewer, viewer,
        feedFlag, viewer,
        author, author,
        tag, tag,
        favorited, favorited,
        limit, offset);
    auto count = co_await db()->execSqlCoro(
        "SELECT COUNT(*) AS n FROM articles a JOIN users u ON u.id = a.author_id" + kListWhere,
        feedFlag, viewer,
        author, author,
        tag, tag,
        favorited, favorited);

    Json::Value body;
    body["articles"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows)
        body["articles"].append(articleJson(row, false));
    body["articlesCount"] = count[0]["n"].as<int64_t>();
    co_return jsonResponse(body);
}

// Validates tagList: returns the de-duplicated tags, or nullopt if invalid.
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
        std::string tag = t.asString();
        if (!tag.empty() && seen.insert(tag).second)
            tags.push_back(tag);
    }
    return tags;
}

Task<void> replaceTags(int64_t articleId, const std::vector<std::string> &tags)
{
    co_await db()->execSqlCoro("DELETE FROM article_tags WHERE article_id = ?", articleId);
    for (size_t i = 0; i < tags.size(); ++i)
        co_await db()->execSqlCoro(
            "INSERT INTO article_tags (article_id, tag, position) VALUES (?, ?, ?)",
            articleId,
            tags[i],
            static_cast<int64_t>(i));
}

// Returns a slug derived from the title that no other article uses.
Task<std::string> uniqueSlug(const std::string &title, int64_t exceptId)
{
    std::string base = slugify(title);
    std::string slug = base;
    while (true)
    {
        auto rows = co_await db()->execSqlCoro(
            "SELECT 1 FROM articles WHERE slug = ? AND id != ?", slug, exceptId);
        if (rows.empty())
            co_return slug;
        slug = base + "-" + randomHex(4);
    }
}

Task<HttpResponsePtr> createArticle(HttpRequestPtr req)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto body = parseBody(req);
    if (!body || !(*body)["article"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["article"];

    Json::Value errors(Json::objectValue);
    for (const char *field : {"title", "description", "body"})
        if (!isNonEmptyString(in[field]))
            errors[field].append("can't be blank");
    std::vector<std::string> tags;
    if (in.isMember("tagList"))
    {
        auto parsed = parseTags(in["tagList"]);
        if (parsed)
            tags = *parsed;
        else
            errors["tagList"].append("must be an array of strings");
    }
    if (!errors.empty())
        co_return validationResponse(errors);

    std::string title = in["title"].asString();
    int64_t now = nowMillis();
    int64_t id = 0;
    for (int attempt = 0; id == 0; ++attempt)
    {
        std::string slug = co_await uniqueSlug(title, 0);
        try
        {
            auto result = co_await db()->execSqlCoro(
                "INSERT INTO articles (slug, title, description, body, author_id, "
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                slug,
                title,
                in["description"].asString(),
                in["body"].asString(),
                *auth.userId,
                now,
                now);
            id = static_cast<int64_t>(result.insertId());
        }
        catch (const orm::DrogonDbException &)
        {
            // Another request took the same slug in the meantime; retry.
            if (attempt >= 5)
                throw;
        }
    }
    co_await replaceTags(id, tags);
    co_return co_await singleArticle(id, *auth.userId, k201Created);
}

Task<HttpResponsePtr> getArticle(HttpRequestPtr req, std::string slug)
{
    if (slug == "feed")
        co_return co_await listArticles(req, true);
    auto auth = authenticate(req, false);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    co_return co_await singleArticle(ref->id, auth.userId.value_or(0));
}

Task<HttpResponsePtr> updateArticle(HttpRequestPtr req, std::string slug)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (ref->authorId != *auth.userId)
        co_return errorResponse(k403Forbidden, "article", "forbidden");
    auto body = parseBody(req);
    if (!body || !(*body)["article"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["article"];

    auto current = co_await db()->execSqlCoro(
        "SELECT title, description, body, updated_at FROM articles WHERE id = ?", ref->id);
    if (current.empty())
        co_return errorResponse(k404NotFound, "article", "not found");
    std::string title = current[0]["title"].as<std::string>();
    std::string description = current[0]["description"].as<std::string>();
    std::string text = current[0]["body"].as<std::string>();
    int64_t prevUpdated = current[0]["updated_at"].as<int64_t>();

    Json::Value errors(Json::objectValue);
    for (auto [field, target] : {std::pair{"title", &title},
                                 std::pair{"description", &description},
                                 std::pair{"body", &text}})
    {
        if (!in.isMember(field))
            continue;
        if (isNonEmptyString(in[field]))
            *target = in[field].asString();
        else
            errors[field].append("can't be blank");
    }
    std::optional<std::vector<std::string>> tags;
    if (in.isMember("tagList"))
    {
        tags = parseTags(in["tagList"]);
        if (!tags)
            errors["tagList"].append("must be an array of strings");
    }
    if (!errors.empty())
        co_return validationResponse(errors);

    std::string newSlug = slug;
    if (in.isMember("title"))
        newSlug = co_await uniqueSlug(title, ref->id);
    // Keep updatedAt strictly increasing even for updates within the same ms.
    int64_t now = std::max(nowMillis(), prevUpdated + 1);
    co_await db()->execSqlCoro(
        "UPDATE articles SET slug = ?, title = ?, description = ?, body = ?, "
        "updated_at = ? WHERE id = ?",
        newSlug,
        title,
        description,
        text,
        now,
        ref->id);
    if (tags)
        co_await replaceTags(ref->id, *tags);
    co_return co_await singleArticle(ref->id, *auth.userId);
}

Task<HttpResponsePtr> deleteArticle(HttpRequestPtr req, std::string slug)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (ref->authorId != *auth.userId)
        co_return errorResponse(k403Forbidden, "article", "forbidden");
    co_await db()->execSqlCoro("DELETE FROM articles WHERE id = ?", ref->id);
    co_return noContent();
}

Task<HttpResponsePtr> setFavorite(HttpRequestPtr req, std::string slug, bool favorite)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto ref = co_await findArticleRef(slug);
    if (!ref)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (favorite)
        co_await db()->execSqlCoro(
            "INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)",
            *auth.userId,
            ref->id);
    else
        co_await db()->execSqlCoro(
            "DELETE FROM favorites WHERE user_id = ? AND article_id = ?",
            *auth.userId,
            ref->id);
    co_return co_await singleArticle(ref->id, *auth.userId);
}

Task<HttpResponsePtr> listTags(HttpRequestPtr)
{
    auto rows = co_await db()->execSqlCoro(
        "SELECT tag FROM article_tags GROUP BY tag ORDER BY COUNT(*) DESC, tag");
    Json::Value body;
    body["tags"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows)
        body["tags"].append(row["tag"].as<std::string>());
    co_return jsonResponse(body);
}
}  // namespace

Task<std::optional<ArticleRef>> findArticleRef(const std::string &slug)
{
    auto rows = co_await db()->execSqlCoro(
        "SELECT id, author_id FROM articles WHERE slug = ?", slug);
    if (rows.empty())
        co_return std::nullopt;
    co_return ArticleRef{rows[0]["id"].as<int64_t>(), rows[0]["author_id"].as<int64_t>()};
}

Json::Value authorJson(const orm::Row &row,
                       const char *usernameCol,
                       const char *bioCol,
                       const char *imageCol,
                       const char *followingCol)
{
    Json::Value p;
    p["username"] = row[usernameCol].as<std::string>();
    p["bio"] = nullableText(row[bioCol]);
    p["image"] = nullableText(row[imageCol]);
    p["following"] = row[followingCol].as<int64_t>() != 0;
    return p;
}

void registerArticleRoutes()
{
    app().registerHandler(
        "/api/articles",
        [](HttpRequestPtr req) -> Task<HttpResponsePtr> {
            co_return co_await listArticles(req, false);
        },
        {Get});
    app().registerHandler("/api/articles", &createArticle, {Post});
    app().registerHandler("/api/articles/{slug}", &getArticle, {Get});
    app().registerHandler("/api/articles/{slug}", &updateArticle, {Put});
    app().registerHandler("/api/articles/{slug}", &deleteArticle, {Delete});
    app().registerHandler(
        "/api/articles/{slug}/favorite",
        [](HttpRequestPtr req, std::string slug) -> Task<HttpResponsePtr> {
            co_return co_await setFavorite(req, slug, true);
        },
        {Post});
    app().registerHandler(
        "/api/articles/{slug}/favorite",
        [](HttpRequestPtr req, std::string slug) -> Task<HttpResponsePtr> {
            co_return co_await setFavorite(req, slug, false);
        },
        {Delete});
    app().registerHandler("/api/tags", &listTags, {Get});
}
