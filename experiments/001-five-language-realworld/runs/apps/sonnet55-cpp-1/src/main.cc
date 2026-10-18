// RealWorld backend API: Drogon + Drogon ORM (DbClient) + SQLite.
#include <drogon/drogon.h>

#include <sqlite3.h>

#include <coroutine>
#include <thread>

#include "util.h"

using namespace drogon;
using namespace drogon::orm;
using rw::errorResp;
using rw::jsonResp;

namespace
{

std::string gSecret;

const char *kSchema[] = {
    "CREATE TABLE IF NOT EXISTS users ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " email TEXT NOT NULL UNIQUE,"
    " username TEXT NOT NULL UNIQUE,"
    " password_hash TEXT NOT NULL,"
    " bio TEXT,"
    " image TEXT)",
    "CREATE TABLE IF NOT EXISTS articles ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " slug TEXT NOT NULL UNIQUE,"
    " title TEXT NOT NULL,"
    " description TEXT NOT NULL,"
    " body TEXT NOT NULL,"
    " author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " created_at TEXT NOT NULL,"
    " updated_at TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id)",
    "CREATE TABLE IF NOT EXISTS article_tags ("
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " tag TEXT NOT NULL,"
    " PRIMARY KEY (article_id, tag))",
    "CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag)",
    "CREATE TABLE IF NOT EXISTS favorites ("
    " user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " PRIMARY KEY (user_id, article_id))",
    "CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id)",
    "CREATE TABLE IF NOT EXISTS follows ("
    " follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " PRIMARY KEY (follower_id, followee_id))",
    "CREATE TABLE IF NOT EXISTS comments ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " body TEXT NOT NULL,"
    " created_at TEXT NOT NULL,"
    " updated_at TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)",
};

using Handler = Task<HttpResponsePtr>;

// Commits a transaction and suspends until the COMMIT has finished. Drogon commits when the last
// reference to the transaction is dropped, but asynchronously: reading from another pooled connection
// right after tx.reset() could still observe the old data.
struct CommitAwaiter
{
    std::shared_ptr<Transaction> tx;
    bool await_ready() const noexcept { return false; }
    void await_suspend(std::coroutine_handle<> h)
    {
        auto t = std::move(tx);  // do not touch `this` after the coroutine may have resumed
        t->setCommitCallback([h](bool) { h.resume(); });
        t.reset();
    }
    void await_resume() const noexcept {}
};

CommitAwaiter commitTx(std::shared_ptr<Transaction> tx)
{
    return CommitAwaiter{std::move(tx)};
}

// ---------- request helpers ----------

struct Auth
{
    int64_t userId{0};
    HttpResponsePtr error;  // set when authentication fails
};

// Authenticates the request. When `required` is false, a missing token is allowed
// (userId stays 0); an invalid token is always rejected.
Auth authenticate(const HttpRequestPtr &req, bool required)
{
    Auth a;
    std::string h = req->getHeader("Authorization");
    std::string token;
    if (h.rfind("Token ", 0) == 0)
        token = h.substr(6);
    else if (h.rfind("Bearer ", 0) == 0)
        token = h.substr(7);
    if (token.empty())
    {
        if (required) a.error = errorResp(k401Unauthorized, "token", "is missing");
        return a;
    }
    auto id = rw::jwtVerify(token, gSecret);
    if (!id)
    {
        a.error = errorResp(k401Unauthorized, "token", "is invalid");
        return a;
    }
    a.userId = *id;
    return a;
}

// Returns the JSON object stored under `key` in the request body (or null).
const Json::Value *bodyObject(const HttpRequestPtr &req, const char *key, Json::Value &holder)
{
    auto j = req->getJsonObject();
    if (!j || !j->isObject()) return nullptr;
    holder = (*j)[key];
    if (!holder.isObject()) return nullptr;
    return &holder;
}

bool isBlank(const Json::Value &v, const char *key)
{
    if (!v.isMember(key) || !v[key].isString()) return true;
    const std::string s = v[key].asString();
    return s.find_first_not_of(" \t\r\n") == std::string::npos;
}

HttpResponsePtr malformed(const char *key)
{
    return errorResp(k422UnprocessableEntity, key, "can't be blank");
}

Json::Value nullableStr(const Row &row, const char *col)
{
    if (row[col].isNull()) return Json::nullValue;
    return Json::Value(row[col].as<std::string>());
}

Json::Value userJson(const Row &row)
{
    Json::Value u;
    u["email"] = row["email"].as<std::string>();
    u["username"] = row["username"].as<std::string>();
    u["bio"] = nullableStr(row, "bio");
    u["image"] = nullableStr(row, "image");
    u["token"] = rw::jwtSign(row["id"].as<int64_t>(), gSecret);
    Json::Value r;
    r["user"] = u;
    return r;
}

Json::Value profileJson(const Row &row, bool following)
{
    Json::Value p;
    p["username"] = row["username"].as<std::string>();
    p["bio"] = nullableStr(row, "bio");
    p["image"] = nullableStr(row, "image");
    p["following"] = following;
    return p;
}

// ---------- SQL fragments ----------

// Placeholders: viewer id (favorited), viewer id (following).
const char *kArticleSelect =
    "SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at,"
    " a.author_id, u.username, u.bio, u.image,"
    " (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS fav_count,"
    " EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ?) AS favorited,"
    " EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followee_id = a.author_id) AS following,"
    " (SELECT group_concat(tag, char(31)) FROM"
    "   (SELECT tag FROM article_tags t WHERE t.article_id = a.id ORDER BY tag)) AS tags"
    " FROM articles a JOIN users u ON u.id = a.author_id ";

Json::Value articleJson(const Row &row, bool withBody)
{
    Json::Value a;
    a["slug"] = row["slug"].as<std::string>();
    a["title"] = row["title"].as<std::string>();
    a["description"] = row["description"].as<std::string>();
    if (withBody) a["body"] = row["body"].as<std::string>();
    Json::Value tags(Json::arrayValue);
    if (!row["tags"].isNull())
    {
        std::string t = row["tags"].as<std::string>();
        size_t start = 0;
        while (start <= t.size())
        {
            size_t e = t.find('\x1f', start);
            if (e == std::string::npos) e = t.size();
            if (e > start) tags.append(t.substr(start, e - start));
            start = e + 1;
        }
    }
    a["tagList"] = tags;
    a["createdAt"] = row["created_at"].as<std::string>();
    a["updatedAt"] = row["updated_at"].as<std::string>();
    a["favorited"] = row["favorited"].as<int64_t>() != 0;
    a["favoritesCount"] = static_cast<Json::Int64>(row["fav_count"].as<int64_t>());
    Json::Value au;
    au["username"] = row["username"].as<std::string>();
    au["bio"] = nullableStr(row, "bio");
    au["image"] = nullableStr(row, "image");
    au["following"] = row["following"].as<int64_t>() != 0;
    a["author"] = au;
    return a;
}

// Loads a single article by slug as the viewer. Returns null Json when not found.
Task<std::optional<Json::Value>> loadArticle(const std::string &slug, int64_t viewer, bool withBody = true)
{
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro(std::string(kArticleSelect) + "WHERE a.slug = ?", viewer, viewer, slug);
    if (r.empty()) co_return std::nullopt;
    co_return articleJson(r[0], withBody);
}

int64_t intParam(const HttpRequestPtr &req, const char *name, int64_t def, int64_t lo, int64_t hi)
{
    std::string s = req->getParameter(name);
    if (s.empty()) return def;
    try
    {
        size_t used = 0;
        long long v = std::stoll(s, &used);
        if (used != s.size()) return def;
        return std::clamp<int64_t>(v, lo, hi);
    }
    catch (...)
    {
        return def;
    }
}

Task<std::optional<Row>> findUserByName(const std::string &name)
{
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro("SELECT * FROM users WHERE username = ?", name);
    if (r.empty()) co_return std::nullopt;
    co_return r[0];
}

// Collects tags from the body, de-duplicated.
std::vector<std::string> readTags(const Json::Value &art)
{
    std::vector<std::string> tags;
    if (art.isMember("tagList") && art["tagList"].isArray())
        for (const auto &t : art["tagList"])
            if (t.isString() && !t.asString().empty() &&
                std::find(tags.begin(), tags.end(), t.asString()) == tags.end())
                tags.push_back(t.asString());
    return tags;
}

bool isUniqueViolation(const std::exception_ptr &ep)
{
    try
    {
        std::rethrow_exception(ep);
    }
    catch (const std::exception &e)
    {
        return std::string(e.what()).find("UNIQUE") != std::string::npos;
    }
    return false;
}

// ---------- users ----------

Handler registerUser(HttpRequestPtr req)
{
    Json::Value holder;
    const Json::Value *u = bodyObject(req, "user", holder);
    if (!u) co_return errorResp(k422UnprocessableEntity, "user", "can't be blank");
    if (isBlank(*u, "username")) co_return malformed("username");
    if (isBlank(*u, "email")) co_return malformed("email");
    if (isBlank(*u, "password")) co_return malformed("password");
    std::string password = (*u)["password"].asString();
    if (password.size() < 8)
        co_return errorResp(k422UnprocessableEntity, "password", "is too short (minimum is 8 characters)");
    std::string username = (*u)["username"].asString();
    std::string email = (*u)["email"].asString();

    auto db = app().getDbClient();
    auto dupU = co_await db->execSqlCoro("SELECT 1 FROM users WHERE username = ?", username);
    if (!dupU.empty()) co_return errorResp(k409Conflict, "username", "has already been taken");
    auto dupE = co_await db->execSqlCoro("SELECT 1 FROM users WHERE email = ?", email);
    if (!dupE.empty()) co_return errorResp(k409Conflict, "email", "has already been taken");
    try
    {
        co_await db->execSqlCoro("INSERT INTO users (email, username, password_hash) VALUES (?, ?, ?)", email,
                                 username, rw::hashPassword(password));
    }
    catch (...)
    {
        if (isUniqueViolation(std::current_exception()))
            co_return errorResp(k409Conflict, "username", "has already been taken");
        throw;
    }
    auto r = co_await db->execSqlCoro("SELECT * FROM users WHERE username = ?", username);
    co_return jsonResp(userJson(r[0]), k201Created);
}

Handler loginUser(HttpRequestPtr req)
{
    Json::Value holder;
    const Json::Value *u = bodyObject(req, "user", holder);
    if (!u) co_return errorResp(k422UnprocessableEntity, "user", "can't be blank");
    if (isBlank(*u, "email")) co_return malformed("email");
    if (isBlank(*u, "password")) co_return malformed("password");
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro("SELECT * FROM users WHERE email = ?", (*u)["email"].asString());
    if (r.empty() || !rw::verifyPassword((*u)["password"].asString(), r[0]["password_hash"].as<std::string>()))
        co_return errorResp(k401Unauthorized, "credentials", "invalid");
    co_return jsonResp(userJson(r[0]));
}

Handler currentUser(HttpRequestPtr req)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro("SELECT * FROM users WHERE id = ?", a.userId);
    if (r.empty()) co_return errorResp(k401Unauthorized, "token", "is invalid");
    co_return jsonResp(userJson(r[0]));
}

Handler updateUser(HttpRequestPtr req)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    Json::Value holder;
    const Json::Value *u = bodyObject(req, "user", holder);
    if (!u) co_return errorResp(k422UnprocessableEntity, "user", "can't be blank");
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT * FROM users WHERE id = ?", a.userId);
    if (cur.empty()) co_return errorResp(k401Unauthorized, "token", "is invalid");

    std::string email = cur[0]["email"].as<std::string>();
    std::string username = cur[0]["username"].as<std::string>();
    std::string hash = cur[0]["password_hash"].as<std::string>();
    Json::Value bio = nullableStr(cur[0], "bio");
    Json::Value image = nullableStr(cur[0], "image");

    // email / username / password must be non-blank strings when present (null is rejected too)
    for (const char *k : {"email", "username", "password"})
        if (u->isMember(k) && isBlank(*u, k)) co_return malformed(k);
    if (u->isMember("password"))
    {
        if ((*u)["password"].asString().size() < 8)
            co_return errorResp(k422UnprocessableEntity, "password", "is too short (minimum is 8 characters)");
        hash = rw::hashPassword((*u)["password"].asString());
    }
    if (u->isMember("email")) email = (*u)["email"].asString();
    if (u->isMember("username")) username = (*u)["username"].asString();
    // Empty strings are normalized to null.
    for (auto [key, target] : {std::pair<const char *, Json::Value *>{"bio", &bio}, {"image", &image}})
        if (u->isMember(key) && ((*u)[key].isString() || (*u)[key].isNull()))
            *target = (*u)[key].isString() && (*u)[key].asString().empty() ? Json::Value() : (*u)[key];

    auto dupU = co_await db->execSqlCoro("SELECT 1 FROM users WHERE username = ? AND id <> ?", username, a.userId);
    if (!dupU.empty()) co_return errorResp(k409Conflict, "username", "has already been taken");
    auto dupE = co_await db->execSqlCoro("SELECT 1 FROM users WHERE email = ? AND id <> ?", email, a.userId);
    if (!dupE.empty()) co_return errorResp(k409Conflict, "email", "has already been taken");

    // Nullable columns: pass std::optional-like via NULL-aware SQL (empty -> NULL is not applied).
    std::string bioStr = bio.isNull() ? "" : bio.asString();
    std::string imageStr = image.isNull() ? "" : image.asString();
    co_await db->execSqlCoro(
        "UPDATE users SET email = ?, username = ?, password_hash = ?,"
        " bio = CASE WHEN ? THEN NULL ELSE ? END, image = CASE WHEN ? THEN NULL ELSE ? END WHERE id = ?",
        email, username, hash, static_cast<int64_t>(bio.isNull()), bioStr, static_cast<int64_t>(image.isNull()),
        imageStr, a.userId);
    auto r = co_await db->execSqlCoro("SELECT * FROM users WHERE id = ?", a.userId);
    co_return jsonResp(userJson(r[0]));
}

// ---------- profiles ----------

Handler getProfile(HttpRequestPtr req, std::string username)
{
    Auth a = authenticate(req, false);
    if (a.error) co_return a.error;
    auto u = co_await findUserByName(username);
    if (!u) co_return errorResp(k404NotFound, "profile", "not found");
    auto db = app().getDbClient();
    auto f = co_await db->execSqlCoro("SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = ?", a.userId,
                                      (*u)["id"].as<int64_t>());
    Json::Value r;
    r["profile"] = profileJson(*u, !f.empty());
    co_return jsonResp(r);
}

Handler followUser(HttpRequestPtr req, std::string username, bool follow)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    auto u = co_await findUserByName(username);
    if (!u) co_return errorResp(k404NotFound, "profile", "not found");
    auto db = app().getDbClient();
    int64_t target = (*u)["id"].as<int64_t>();
    if (follow)
        co_await db->execSqlCoro("INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)", a.userId,
                                 target);
    else
        co_await db->execSqlCoro("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", a.userId, target);
    Json::Value r;
    r["profile"] = profileJson(*u, follow);
    co_return jsonResp(r);
}

// ---------- articles ----------

Handler listArticles(HttpRequestPtr req)
{
    Auth a = authenticate(req, false);
    if (a.error) co_return a.error;
    std::string tag = req->getParameter("tag");
    std::string author = req->getParameter("author");
    std::string favorited = req->getParameter("favorited");
    int64_t limit = intParam(req, "limit", 20, 0, 100000);
    int64_t offset = intParam(req, "offset", 0, 0, INT64_MAX / 2);
    const char *where =
        "WHERE (? = '' OR EXISTS(SELECT 1 FROM article_tags t WHERE t.article_id = a.id AND t.tag = ?))"
        " AND (? = '' OR u.username = ?)"
        " AND (? = '' OR EXISTS(SELECT 1 FROM favorites f JOIN users fu ON fu.id = f.user_id"
        "   WHERE f.article_id = a.id AND fu.username = ?)) ";
    auto db = app().getDbClient();
    auto cnt = co_await db->execSqlCoro(
        std::string("SELECT COUNT(*) AS c FROM articles a JOIN users u ON u.id = a.author_id ") + where, tag, tag,
        author, author, favorited, favorited);
    auto rows = co_await db->execSqlCoro(std::string(kArticleSelect) + where + "ORDER BY a.id DESC LIMIT ? OFFSET ?",
                                         a.userId, a.userId, tag, tag, author, author, favorited, favorited, limit,
                                         offset);
    Json::Value out;
    out["articles"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows) out["articles"].append(articleJson(row, false));
    out["articlesCount"] = static_cast<Json::Int64>(cnt[0]["c"].as<int64_t>());
    co_return jsonResp(out);
}

Handler feedArticles(HttpRequestPtr req)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    int64_t limit = intParam(req, "limit", 20, 0, 100000);
    int64_t offset = intParam(req, "offset", 0, 0, INT64_MAX / 2);
    const char *where = "WHERE a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?) ";
    auto db = app().getDbClient();
    auto cnt = co_await db->execSqlCoro(
        std::string("SELECT COUNT(*) AS c FROM articles a JOIN users u ON u.id = a.author_id ") + where, a.userId);
    auto rows = co_await db->execSqlCoro(std::string(kArticleSelect) + where + "ORDER BY a.id DESC LIMIT ? OFFSET ?",
                                         a.userId, a.userId, a.userId, limit, offset);
    Json::Value out;
    out["articles"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows) out["articles"].append(articleJson(row, false));
    out["articlesCount"] = static_cast<Json::Int64>(cnt[0]["c"].as<int64_t>());
    co_return jsonResp(out);
}

Handler getArticle(HttpRequestPtr req, std::string slug)
{
    Auth a = authenticate(req, false);
    if (a.error) co_return a.error;
    auto art = co_await loadArticle(slug, a.userId);
    if (!art) co_return errorResp(k404NotFound, "article", "not found");
    Json::Value r;
    r["article"] = *art;
    co_return jsonResp(r);
}

Handler createArticle(HttpRequestPtr req)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    Json::Value holder;
    const Json::Value *art = bodyObject(req, "article", holder);
    if (!art) co_return errorResp(k422UnprocessableEntity, "article", "can't be blank");
    for (const char *k : {"title", "description", "body"})
        if (isBlank(*art, k)) co_return malformed(k);
    std::string title = (*art)["title"].asString();
    if (art->isMember("tagList") && !(*art)["tagList"].isArray())
        co_return errorResp(k422UnprocessableEntity, "tagList", "must be an array");
    auto tags = readTags(*art);

    auto db = app().getDbClient();
    std::string base = rw::slugify(title);
    std::string slug = base;
    auto exists = co_await db->execSqlCoro("SELECT 1 FROM articles WHERE slug = ?", slug);
    if (!exists.empty()) slug = base + "-" + rw::randomHex(4);

    std::string now = rw::nowIso();
    auto tx = co_await db->newTransactionCoro();
    auto ins = co_await tx->execSqlCoro(
        "INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)",
        slug, title, (*art)["description"].asString(), (*art)["body"].asString(), a.userId, now, now);
    int64_t id = static_cast<int64_t>(ins.insertId());
    for (const auto &t : tags)
        co_await tx->execSqlCoro("INSERT OR IGNORE INTO article_tags (article_id, tag) VALUES (?, ?)", id, t);
    co_await commitTx(std::move(tx));

    auto out = co_await loadArticle(slug, a.userId);
    Json::Value r;
    r["article"] = *out;
    co_return jsonResp(r, k201Created);
}

Handler updateArticle(HttpRequestPtr req, std::string slug)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT * FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    if (cur[0]["author_id"].as<int64_t>() != a.userId) co_return errorResp(k403Forbidden, "article", "forbidden");
    Json::Value holder;
    const Json::Value *art = bodyObject(req, "article", holder);
    if (!art) co_return errorResp(k422UnprocessableEntity, "article", "can't be blank");
    std::string title = cur[0]["title"].as<std::string>();
    std::string description = cur[0]["description"].as<std::string>();
    std::string body = cur[0]["body"].as<std::string>();
    for (const char *k : {"title", "description", "body"})
        if (art->isMember(k) && isBlank(*art, k)) co_return malformed(k);
    if (art->isMember("tagList") && !(*art)["tagList"].isArray())
        co_return errorResp(k422UnprocessableEntity, "tagList", "must be an array");
    if (art->isMember("title")) title = (*art)["title"].asString();
    if (art->isMember("description")) description = (*art)["description"].asString();
    if (art->isMember("body")) body = (*art)["body"].asString();
    int64_t id = cur[0]["id"].as<int64_t>();

    auto tx = co_await db->newTransactionCoro();
    co_await tx->execSqlCoro(
        "UPDATE articles SET title = ?, description = ?, body = ?, updated_at = ? WHERE id = ?", title, description,
        body, rw::nowIso(), id);
    if (art->isMember("tagList"))
    {
        co_await tx->execSqlCoro("DELETE FROM article_tags WHERE article_id = ?", id);
        for (const auto &t : readTags(*art))
            co_await tx->execSqlCoro("INSERT OR IGNORE INTO article_tags (article_id, tag) VALUES (?, ?)", id, t);
    }
    co_await commitTx(std::move(tx));

    auto out = co_await loadArticle(slug, a.userId);
    Json::Value r;
    r["article"] = *out;
    co_return jsonResp(r);
}

Handler deleteArticle(HttpRequestPtr req, std::string slug)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT id, author_id FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    if (cur[0]["author_id"].as<int64_t>() != a.userId) co_return errorResp(k403Forbidden, "article", "forbidden");
    co_await db->execSqlCoro("DELETE FROM articles WHERE id = ?", cur[0]["id"].as<int64_t>());
    auto r = HttpResponse::newHttpResponse();
    r->setStatusCode(k204NoContent);
    co_return r;
}

// ---------- favorites ----------

Handler favoriteArticle(HttpRequestPtr req, std::string slug, bool favorite)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    int64_t id = cur[0]["id"].as<int64_t>();
    if (favorite)
        co_await db->execSqlCoro("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)", a.userId, id);
    else
        co_await db->execSqlCoro("DELETE FROM favorites WHERE user_id = ? AND article_id = ?", a.userId, id);
    auto art = co_await loadArticle(slug, a.userId);
    Json::Value r;
    r["article"] = *art;
    co_return jsonResp(r);
}

// ---------- comments ----------

Json::Value commentJson(const Row &row)
{
    Json::Value c;
    c["id"] = static_cast<Json::Int64>(row["id"].as<int64_t>());
    c["createdAt"] = row["created_at"].as<std::string>();
    c["updatedAt"] = row["updated_at"].as<std::string>();
    c["body"] = row["body"].as<std::string>();
    Json::Value au;
    au["username"] = row["username"].as<std::string>();
    au["bio"] = nullableStr(row, "bio");
    au["image"] = nullableStr(row, "image");
    au["following"] = row["following"].as<int64_t>() != 0;
    c["author"] = au;
    return c;
}

const char *kCommentSelect =
    "SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,"
    " EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followee_id = c.author_id) AS following"
    " FROM comments c JOIN users u ON u.id = c.author_id ";

Handler listComments(HttpRequestPtr req, std::string slug)
{
    Auth a = authenticate(req, false);
    if (a.error) co_return a.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    auto rows = co_await db->execSqlCoro(std::string(kCommentSelect) + "WHERE c.article_id = ? ORDER BY c.id ASC",
                                         a.userId, cur[0]["id"].as<int64_t>());
    Json::Value out;
    out["comments"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows) out["comments"].append(commentJson(row));
    co_return jsonResp(out);
}

Handler addComment(HttpRequestPtr req, std::string slug)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    Json::Value holder;
    const Json::Value *c = bodyObject(req, "comment", holder);
    if (!c || isBlank(*c, "body")) co_return malformed("body");
    std::string now = rw::nowIso();
    auto ins = co_await db->execSqlCoro(
        "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
        cur[0]["id"].as<int64_t>(), a.userId, (*c)["body"].asString(), now, now);
    auto rows = co_await db->execSqlCoro(std::string(kCommentSelect) + "WHERE c.id = ?", a.userId,
                                         static_cast<int64_t>(ins.insertId()));
    Json::Value out;
    out["comment"] = commentJson(rows[0]);
    co_return jsonResp(out, k201Created);
}

Handler deleteComment(HttpRequestPtr req, std::string slug, std::string idStr)
{
    Auth a = authenticate(req, true);
    if (a.error) co_return a.error;
    auto db = app().getDbClient();
    auto art = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (art.empty()) co_return errorResp(k404NotFound, "article", "not found");
    int64_t cid = 0;
    try
    {
        cid = std::stoll(idStr);
    }
    catch (...)
    {
        co_return errorResp(k404NotFound, "comment", "not found");
    }
    auto c = co_await db->execSqlCoro("SELECT author_id FROM comments WHERE id = ? AND article_id = ?", cid,
                                      art[0]["id"].as<int64_t>());
    if (c.empty()) co_return errorResp(k404NotFound, "comment", "not found");
    if (c[0]["author_id"].as<int64_t>() != a.userId) co_return errorResp(k403Forbidden, "comment", "forbidden");
    co_await db->execSqlCoro("DELETE FROM comments WHERE id = ?", cid);
    auto r = HttpResponse::newHttpResponse();
    r->setStatusCode(k204NoContent);
    co_return r;
}

// ---------- tags ----------

Handler listTags(HttpRequestPtr)
{
    auto db = app().getDbClient();
    auto rows = co_await db->execSqlCoro("SELECT DISTINCT tag FROM article_tags ORDER BY tag");
    Json::Value out;
    out["tags"] = Json::Value(Json::arrayValue);
    for (const auto &row : rows) out["tags"].append(row["tag"].as<std::string>());
    co_return jsonResp(out);
}

void registerRoutes()
{
    auto &app = drogon::app();
    app.registerHandler("/api/users", &registerUser, {Post});
    app.registerHandler("/api/users/login", &loginUser, {Post});
    app.registerHandler("/api/user", &currentUser, {Get});
    app.registerHandler("/api/user", &updateUser, {Put});
    app.registerHandler("/api/profiles/{username}", &getProfile, {Get});
    app.registerHandler(
        "/api/profiles/{username}/follow",
        [](HttpRequestPtr req, std::string u) { return followUser(std::move(req), std::move(u), true); }, {Post});
    app.registerHandler(
        "/api/profiles/{username}/follow",
        [](HttpRequestPtr req, std::string u) { return followUser(std::move(req), std::move(u), false); },
        {Delete});
    app.registerHandler("/api/articles", &listArticles, {Get});
    app.registerHandler("/api/articles", &createArticle, {Post});
    app.registerHandler("/api/articles/feed", &feedArticles, {Get});
    app.registerHandler("/api/articles/{slug}", &getArticle, {Get});
    app.registerHandler("/api/articles/{slug}", &updateArticle, {Put});
    app.registerHandler("/api/articles/{slug}", &deleteArticle, {Delete});
    app.registerHandler(
        "/api/articles/{slug}/favorite",
        [](HttpRequestPtr req, std::string s) { return favoriteArticle(std::move(req), std::move(s), true); },
        {Post});
    app.registerHandler(
        "/api/articles/{slug}/favorite",
        [](HttpRequestPtr req, std::string s) { return favoriteArticle(std::move(req), std::move(s), false); },
        {Delete});
    app.registerHandler("/api/articles/{slug}/comments", &listComments, {Get});
    app.registerHandler("/api/articles/{slug}/comments", &addComment, {Post});
    app.registerHandler("/api/articles/{slug}/comments/{id}", &deleteComment, {Delete});
    app.registerHandler("/api/tags", &listTags, {Get});
}

}  // namespace

// Enables WAL and creates the schema once, before Drogon opens its pooled connections.
// This avoids lock contention between pool connections initializing concurrently.
bool initDatabase(const std::string &file)
{
    sqlite3 *db = nullptr;
    if (sqlite3_open(file.c_str(), &db) != SQLITE_OK)
    {
        std::fprintf(stderr, "cannot open database %s: %s\n", file.c_str(), sqlite3_errmsg(db));
        sqlite3_close(db);
        return false;
    }
    sqlite3_busy_timeout(db, 5000);
    bool ok = true;
    auto run = [&](const char *sql)
    {
        char *err = nullptr;
        if (sqlite3_exec(db, sql, nullptr, nullptr, &err) != SQLITE_OK)
        {
            std::fprintf(stderr, "database init failed (%s): %s\n", sql, err ? err : "?");
            sqlite3_free(err);
            ok = false;
        }
    };
    run("PRAGMA journal_mode=WAL");
    for (const char *sql : kSchema) run(sql);
    sqlite3_close(db);
    // Drogon calls sqlite3_config(SQLITE_CONFIG_MULTITHREAD) on startup, which fails once SQLite
    // has been initialized. Shut the library down so that call succeeds.
    sqlite3_shutdown();
    return ok;
}

// Newer Drogon (1.9.14+) can run initialization queries on every pooled SQLite connection.
template <class Cfg>
concept HasInitQueries = requires(Cfg c) { c.initializationQueries; };

// Registers the SQLite client pool. Every connection gets busy_timeout=5000 ms and foreign keys on.
// WAL is persistent in the database file and was already enabled by initDatabase().
template <class Cfg>
void addSqliteClient(const std::string &file, size_t connections)
{
    Cfg cfg;
    cfg.connectionNumber = connections;
    cfg.filename = file;
    cfg.name = "default";
    cfg.timeout = -1.0;
    if constexpr (HasInitQueries<Cfg>)
    {
        cfg.initializationQueries = {"PRAGMA busy_timeout=5000", "PRAGMA journal_mode=WAL",
                                     "PRAGMA foreign_keys=ON", "PRAGMA synchronous=NORMAL"};
    }
    else
    {
        // Older Drogon cannot run per-connection init queries: configure each connection opened
        // in this process through a SQLite auto-extension instead. This only works when the
        // executable and libdrogon share one libsqlite3 (true for the Linux packages).
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
        sqlite3_auto_extension(reinterpret_cast<void (*)(void)>(
            +[](sqlite3 *conn, char **, const void *) -> int
            {
                sqlite3_busy_timeout(conn, 5000);
                sqlite3_exec(conn, "PRAGMA foreign_keys=ON; PRAGMA synchronous=NORMAL", nullptr, nullptr, nullptr);
                return SQLITE_OK;
            }));
#pragma GCC diagnostic pop
    }
    drogon::app().addDbClient(cfg);
}

int main()
{
    const int port = std::atoi(rw::envOr("PORT", "8080").c_str());
    const std::string dbFile = rw::envOr("DATABASE_PATH", "realworld.db");
    gSecret = rw::envOr("JWT_SECRET", "realworld-dev-secret-change-me");
    unsigned cores = std::thread::hardware_concurrency();
    if (cores == 0) cores = 1;

    if (!initDatabase(dbFile)) return 1;

    auto &app = drogon::app();
    addSqliteClient<orm::Sqlite3Config>(dbFile, cores);
    app.setThreadNum(cores);
    app.addListener("0.0.0.0", static_cast<uint16_t>(port));
    app.setLogLevel(trantor::Logger::kWarn);
    app.setClientMaxBodySize(8 * 1024 * 1024);
    registerRoutes();

    app.run();
    return 0;
}
