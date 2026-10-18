// RealWorld REST API handlers (coroutine based, raw SQL through Drogon's DbClient).
#include "api.h"

#include <drogon/drogon.h>

#include <map>
#include <set>
#include <vector>

#include "util.h"

using namespace drogon;
using drogon::orm::DrogonDbException;
using drogon::orm::Result;
using drogon::orm::Row;
using Resp = HttpResponsePtr;

namespace
{
// ---------- response helpers ----------
Resp jsonResp(const Json::Value &v, HttpStatusCode code = k200OK)
{
    auto r = HttpResponse::newHttpJsonResponse(v);
    r->setStatusCode(code);
    return r;
}

Json::Value errorBody(const std::string &field, const std::string &msg)
{
    Json::Value e;
    e["errors"][field].append(msg);
    return e;
}

Resp errorResp(HttpStatusCode code, const std::string &field, const std::string &msg)
{
    return jsonResp(errorBody(field, msg), code);
}

// Collects field errors and renders them as {"errors": {field: [msg, ...]}}.
struct Errors
{
    Json::Value body{Json::objectValue};
    bool any = false;
    void add(const std::string &field, const std::string &msg)
    {
        body["errors"][field].append(msg);
        any = true;
    }
    Resp resp(HttpStatusCode code = k422UnprocessableEntity) const
    {
        return jsonResp(body, code);
    }
};

Resp noContent()
{
    auto r = HttpResponse::newHttpResponse();
    r->setStatusCode(k204NoContent);
    return r;
}

// ---------- request helpers ----------
std::optional<Json::Value> bodyObject(const HttpRequestPtr &req, const char *key)
{
    auto root = util::parseJson(std::string(req->body()));
    if (!root || !root->isObject() || !(*root)[key].isObject()) return std::nullopt;
    return (*root)[key];
}

Resp badBody(const char *key)
{
    Errors e;
    e.add(key, "can't be blank");
    return e.resp();
}

// A required string field that is missing, null, not a string, or whitespace only.
bool blankField(const Json::Value &obj, const char *key)
{
    return !obj.isMember(key) || !obj[key].isString() || util::isBlank(obj[key].asString());
}

bool validEmail(const std::string &e)
{
    auto at = e.find('@');
    return at != std::string::npos && at > 0 && at + 1 < e.size() &&
           e.find(' ') == std::string::npos;
}

int64_t intParam(const HttpRequestPtr &req, const char *name, int64_t def, int64_t lo,
                 int64_t hi)
{
    auto s = req->getParameter(name);
    if (s.empty()) return def;
    try
    {
        size_t pos = 0;
        long long v = std::stoll(s, &pos);
        if (pos != s.size()) return def;
        return std::clamp<int64_t>(v, lo, hi);
    }
    catch (...)
    {
        return def;
    }
}

Json::Value nullable(const Row &row, const char *col)
{
    const auto &f = row[col];
    if (f.isNull()) return Json::Value(Json::nullValue);
    return Json::Value(f.as<std::string>());
}

// ---------- authentication ----------
struct Viewer
{
    bool authenticated = false;
    int64_t id = 0;
    std::string username, email;
    Json::Value bio{Json::nullValue}, image{Json::nullValue};
    Resp error;  // set when a token was supplied but is invalid, or required and missing
};

Task<Viewer> authenticate(const HttpRequestPtr &req, bool required)
{
    Viewer v;
    std::string header = req->getHeader("authorization");
    std::string token;
    for (const char *prefix : {"Token ", "Bearer "})
    {
        std::string p = prefix;
        if (header.size() > p.size() && header.compare(0, p.size(), p) == 0)
            token = header.substr(p.size());
    }
    if (token.empty())
    {
        if (required) v.error = errorResp(k401Unauthorized, "token", "is missing");
        co_return v;
    }
    auto uid = util::jwtVerify(token);
    if (!uid)
    {
        v.error = errorResp(k401Unauthorized, "token", "is invalid");
        co_return v;
    }
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro(
        "SELECT id, username, email, bio, image FROM users WHERE id = ?", (int64_t)*uid);
    if (r.empty())
    {
        v.error = errorResp(k401Unauthorized, "token", "is invalid");
        co_return v;
    }
    v.authenticated = true;
    v.id = r[0]["id"].as<int64_t>();
    v.username = r[0]["username"].as<std::string>();
    v.email = r[0]["email"].as<std::string>();
    v.bio = nullable(r[0], "bio");
    v.image = nullable(r[0], "image");
    co_return v;
}

Json::Value userJson(const Viewer &v)
{
    Json::Value u;
    u["email"] = v.email;
    u["username"] = v.username;
    u["bio"] = v.bio;
    u["image"] = v.image;
    u["token"] = util::jwtCreate(v.id, v.username);
    Json::Value out;
    out["user"] = u;
    return out;
}

Json::Value userJsonFromRow(const Row &row)
{
    Viewer v;
    v.id = row["id"].as<int64_t>();
    v.username = row["username"].as<std::string>();
    v.email = row["email"].as<std::string>();
    v.bio = nullable(row, "bio");
    v.image = nullable(row, "image");
    return userJson(v);
}

Json::Value profileJson(const Row &row, bool following)
{
    Json::Value p;
    p["username"] = row["username"].as<std::string>();
    p["bio"] = nullable(row, "bio");
    p["image"] = nullable(row, "image");
    p["following"] = following;
    return p;
}

// ---------- articles ----------
const char *kArticleSelect =
    "SELECT a.id, a.slug, a.title, a.description, a.body, a.author_id, a.created_at, "
    "a.updated_at, u.username, u.bio, u.image, "
    "(SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS fav_count, "
    "EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ?) AS fav, "
    "EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followed_id = a.author_id) "
    "AS fol "
    "FROM articles a JOIN users u ON u.id = a.author_id ";

// Shared list filter: tag, author, favorited-by, feed (followed authors of a user).
const char *kArticleFilter =
    "WHERE (? = '' OR EXISTS(SELECT 1 FROM article_tags t WHERE t.article_id = a.id AND "
    "t.tag = ?)) "
    "AND (? = '' OR u.username = ?) "
    "AND (? = '' OR EXISTS(SELECT 1 FROM favorites f2 JOIN users fu ON fu.id = f2.user_id "
    "WHERE f2.article_id = a.id AND fu.username = ?)) "
    "AND (? = 0 OR a.author_id IN (SELECT followed_id FROM follows WHERE follower_id = ?)) ";

Json::Value articleJson(const Row &row, const std::vector<std::string> &tags, bool withBody)
{
    Json::Value a;
    a["slug"] = row["slug"].as<std::string>();
    a["title"] = row["title"].as<std::string>();
    a["description"] = row["description"].as<std::string>();
    if (withBody) a["body"] = row["body"].as<std::string>();
    Json::Value tl(Json::arrayValue);
    for (auto &t : tags) tl.append(t);
    a["tagList"] = tl;
    a["createdAt"] = row["created_at"].as<std::string>();
    a["updatedAt"] = row["updated_at"].as<std::string>();
    a["favorited"] = row["fav"].as<int>() != 0;
    a["favoritesCount"] = (Json::Int64)row["fav_count"].as<int64_t>();
    a["author"] = profileJson(row, row["fol"].as<int>() != 0);
    return a;
}

Task<std::map<int64_t, std::vector<std::string>>> loadTags(const Result &rows)
{
    std::map<int64_t, std::vector<std::string>> out;
    if (rows.empty()) co_return out;
    std::string ids;
    for (auto &r : rows)
    {
        if (!ids.empty()) ids += ',';
        ids += std::to_string(r["id"].as<int64_t>());  // integers only: safe to inline
    }
    auto db = app().getDbClient();
    auto t = co_await db->execSqlCoro(
        "SELECT article_id, tag FROM article_tags WHERE article_id IN (" + ids +
        ") ORDER BY article_id, position");
    for (auto &r : t) out[r["article_id"].as<int64_t>()].push_back(r["tag"].as<std::string>());
    co_return out;
}

struct ArticleQuery
{
    std::string tag, author, favorited;
    bool feed = false;
    int64_t limit = 20, offset = 0;
};

Task<Json::Value> listArticles(const Viewer &viewer, const ArticleQuery &q)
{
    auto db = app().getDbClient();
    int64_t feedFlag = q.feed ? 1 : 0;
    auto count = co_await db->execSqlCoro(
        std::string("SELECT COUNT(*) AS c FROM articles a JOIN users u ON u.id = a.author_id ") +
            kArticleFilter,
        q.tag, q.tag, q.author, q.author, q.favorited, q.favorited, feedFlag, viewer.id);
    auto rows = co_await db->execSqlCoro(
        std::string(kArticleSelect) + kArticleFilter +
            "ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
        viewer.id, viewer.id, q.tag, q.tag, q.author, q.author, q.favorited, q.favorited,
        feedFlag, viewer.id, q.limit, q.offset);
    auto tags = co_await loadTags(rows);
    Json::Value out;
    out["articles"] = Json::Value(Json::arrayValue);
    for (auto &r : rows)
        out["articles"].append(articleJson(r, tags[r["id"].as<int64_t>()], false));
    out["articlesCount"] = (Json::Int64)count[0]["c"].as<int64_t>();
    co_return out;
}

// Loads one article; returns null JSON when the slug does not exist.
Task<std::optional<Json::Value>> fetchArticle(const Viewer &viewer, const std::string &slug)
{
    auto db = app().getDbClient();
    auto rows = co_await db->execSqlCoro(
        std::string(kArticleSelect) + "WHERE a.slug = ?", viewer.id, viewer.id, slug);
    if (rows.empty()) co_return std::nullopt;
    auto tags = co_await loadTags(rows);
    Json::Value out;
    out["article"] = articleJson(rows[0], tags[rows[0]["id"].as<int64_t>()], true);
    co_return out;
}

// Parses tagList into a de-duplicated, ordered JSON array string. Returns nullopt if invalid.
std::optional<std::string> tagsToJson(const Json::Value &tl)
{
    if (!tl.isArray()) return std::nullopt;
    Json::Value out(Json::arrayValue);
    std::set<std::string> seen;
    for (auto &t : tl)
    {
        if (!t.isString()) return std::nullopt;
        std::string s = t.asString();
        if (util::isBlank(s) || !seen.insert(s).second) continue;
        out.append(s);
    }
    return util::toJsonString(out);
}

const char *kInsertTags =
    "INSERT OR IGNORE INTO article_tags (article_id, tag, position) "
    "SELECT ?, value, key FROM json_each(?)";

// ---------- handlers ----------
Task<Resp> registerUser(HttpRequestPtr req)
{
    auto u = bodyObject(req, "user");
    if (!u) co_return badBody("user");
    Errors e;
    if (blankField(*u, "username")) e.add("username", "can't be blank");
    if (blankField(*u, "email"))
        e.add("email", "can't be blank");
    else if (!validEmail((*u)["email"].asString()))
        e.add("email", "is invalid");
    if (blankField(*u, "password"))
        e.add("password", "can't be blank");
    else if ((*u)["password"].asString().size() < 8)
        e.add("password", "is too short (minimum is 8 characters)");
    if (e.any) co_return e.resp();

    std::string username = (*u)["username"].asString();
    std::string email = (*u)["email"].asString();
    auto db = app().getDbClient();
    auto dup = co_await db->execSqlCoro(
        "SELECT username, email FROM users WHERE username = ? OR email = ?", username, email);
    for (auto &r : dup)
    {
        if (r["username"].as<std::string>() == username) e.add("username", "has already been taken");
        if (r["email"].as<std::string>() == email) e.add("email", "has already been taken");
    }
    if (e.any) co_return e.resp(k409Conflict);

    std::string now = util::nowIso();
    std::string hash = util::hashPassword((*u)["password"].asString());
    try
    {
        auto r = co_await db->execSqlCoro(
            "INSERT INTO users (username, email, password_hash, created_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?)",
            username, email, hash, now, now);
        Viewer v;
        v.id = (int64_t)r.insertId();
        v.username = username;
        v.email = email;
        co_return jsonResp(userJson(v), k201Created);
    }
    catch (const DrogonDbException &)
    {
        // Lost a race against a concurrent registration with the same name or email.
        e.add("username", "has already been taken");
        co_return e.resp(k409Conflict);
    }
}

Task<Resp> login(HttpRequestPtr req)
{
    auto u = bodyObject(req, "user");
    if (!u) co_return badBody("user");
    Errors e;
    if (blankField(*u, "email")) e.add("email", "can't be blank");
    if (blankField(*u, "password")) e.add("password", "can't be blank");
    if (e.any) co_return e.resp();
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro(
        "SELECT id, username, email, bio, image, password_hash FROM users WHERE email = ?",
        (*u)["email"].asString());
    if (r.empty() ||
        !util::verifyPassword((*u)["password"].asString(), r[0]["password_hash"].as<std::string>()))
        co_return errorResp(k401Unauthorized, "credentials", "invalid");
    co_return jsonResp(userJsonFromRow(r[0]));
}

Task<Resp> currentUser(HttpRequestPtr req)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    co_return jsonResp(userJson(v));
}

Task<Resp> updateUser(HttpRequestPtr req)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto u = bodyObject(req, "user");
    if (!u) co_return badBody("user");

    Errors e;
    std::string username = v.username, email = v.email, newHash;
    std::string bio = v.bio.isNull() ? "" : v.bio.asString();
    std::string image = v.image.isNull() ? "" : v.image.asString();
    for (const char *k : {"username", "email", "password"})
        if (u->isMember(k) && blankField(*u, k)) e.add(k, "can't be blank");
    if (u->isMember("email") && !blankField(*u, "email"))
    {
        email = (*u)["email"].asString();
        if (!validEmail(email)) e.add("email", "is invalid");
    }
    if (u->isMember("username") && !blankField(*u, "username")) username = (*u)["username"].asString();
    if (u->isMember("password") && !blankField(*u, "password"))
    {
        if ((*u)["password"].asString().size() < 8)
            e.add("password", "is too short (minimum is 8 characters)");
        else
            newHash = util::hashPassword((*u)["password"].asString());
    }
    for (const char *k : {"bio", "image"})
    {
        if (!u->isMember(k)) continue;
        const auto &f = (*u)[k];
        if (!f.isNull() && !f.isString())
        {
            e.add(k, "is invalid");
            continue;
        }
        (std::string(k) == "bio" ? bio : image) = f.isNull() ? "" : f.asString();
    }
    if (e.any) co_return e.resp();

    auto db = app().getDbClient();
    auto dup = co_await db->execSqlCoro(
        "SELECT username, email FROM users WHERE (username = ? OR email = ?) AND id <> ?",
        username, email, v.id);
    for (auto &r : dup)
    {
        if (r["username"].as<std::string>() == username) e.add("username", "has already been taken");
        if (r["email"].as<std::string>() == email) e.add("email", "has already been taken");
    }
    if (e.any) co_return e.resp(k409Conflict);

    try
    {
        co_await db->execSqlCoro(
            "UPDATE users SET username = ?, email = ?, bio = NULLIF(?, ''), "
            "image = NULLIF(?, ''), password_hash = CASE WHEN ? = '' THEN password_hash "
            "ELSE ? END, updated_at = ? WHERE id = ?",
            username, email, bio, image, newHash, newHash, util::nowIso(), v.id);
    }
    catch (const DrogonDbException &)
    {
        e.add("username", "has already been taken");
        co_return e.resp(k409Conflict);
    }
    v.username = username;
    v.email = email;
    v.bio = bio.empty() ? Json::Value(Json::nullValue) : Json::Value(bio);
    v.image = image.empty() ? Json::Value(Json::nullValue) : Json::Value(image);
    co_return jsonResp(userJson(v));
}

// ----- profiles -----
Task<Resp> profileResponse(const std::string &username, int64_t viewerId)
{
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro(
        "SELECT u.username, u.bio, u.image, EXISTS(SELECT 1 FROM follows w WHERE "
        "w.follower_id = ? AND w.followed_id = u.id) AS fol FROM users u WHERE u.username = ?",
        viewerId, username);
    if (r.empty()) co_return errorResp(k404NotFound, "profile", "not found");
    Json::Value out;
    out["profile"] = profileJson(r[0], r[0]["fol"].as<int>() != 0);
    co_return jsonResp(out);
}

Task<Resp> getProfile(HttpRequestPtr req, std::string username)
{
    auto v = co_await authenticate(req, false);
    if (v.error) co_return v.error;
    co_return co_await profileResponse(username, v.id);
}

Task<Resp> followUser(HttpRequestPtr req, std::string username)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro("SELECT id FROM users WHERE username = ?", username);
    if (r.empty()) co_return errorResp(k404NotFound, "profile", "not found");
    co_await db->execSqlCoro(
        "INSERT OR IGNORE INTO follows (follower_id, followed_id) VALUES (?, ?)", v.id,
        r[0]["id"].as<int64_t>());
    co_return co_await profileResponse(username, v.id);
}

Task<Resp> unfollowUser(HttpRequestPtr req, std::string username)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto r = co_await db->execSqlCoro("SELECT id FROM users WHERE username = ?", username);
    if (r.empty()) co_return errorResp(k404NotFound, "profile", "not found");
    co_await db->execSqlCoro("DELETE FROM follows WHERE follower_id = ? AND followed_id = ?",
                             v.id, r[0]["id"].as<int64_t>());
    co_return co_await profileResponse(username, v.id);
}

// ----- articles -----
Task<Resp> listArticlesHandler(HttpRequestPtr req)
{
    auto v = co_await authenticate(req, false);
    if (v.error) co_return v.error;
    ArticleQuery q;
    q.tag = req->getParameter("tag");
    q.author = req->getParameter("author");
    q.favorited = req->getParameter("favorited");
    q.limit = intParam(req, "limit", 20, 0, 100);
    q.offset = intParam(req, "offset", 0, 0, INT64_MAX);
    co_return jsonResp(co_await listArticles(v, q));
}

Task<Resp> feedHandler(HttpRequestPtr req)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    ArticleQuery q;
    q.feed = true;
    q.limit = intParam(req, "limit", 20, 0, 100);
    q.offset = intParam(req, "offset", 0, 0, INT64_MAX);
    co_return jsonResp(co_await listArticles(v, q));
}

Task<Resp> getArticle(HttpRequestPtr req, std::string slug)
{
    auto v = co_await authenticate(req, false);
    if (v.error) co_return v.error;
    auto a = co_await fetchArticle(v, slug);
    if (!a) co_return errorResp(k404NotFound, "article", "not found");
    co_return jsonResp(*a);
}

Task<Resp> createArticle(HttpRequestPtr req)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto a = bodyObject(req, "article");
    if (!a) co_return badBody("article");
    Errors e;
    for (const char *k : {"title", "description", "body"})
        if (blankField(*a, k)) e.add(k, "can't be blank");
    std::string tagsJson = "[]";
    if (a->isMember("tagList"))
    {
        auto t = tagsToJson((*a)["tagList"]);
        if (!t)
            e.add("tagList", "is invalid");
        else
            tagsJson = *t;
    }
    if (e.any) co_return e.resp();

    auto db = app().getDbClient();
    std::string base = util::slugify((*a)["title"].asString());
    std::string slug = base;
    auto exists = co_await db->execSqlCoro("SELECT 1 FROM articles WHERE slug = ?", slug);
    if (!exists.empty()) slug = base + "-" + util::randomHex(4);
    std::string now = util::nowIso();
    int64_t id = 0;
    for (int attempt = 0;; ++attempt)
    {
        try
        {
            auto r = co_await db->execSqlCoro(
                "INSERT INTO articles (slug, title, description, body, author_id, created_at, "
                "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                slug, (*a)["title"].asString(), (*a)["description"].asString(),
                (*a)["body"].asString(), v.id, now, now);
            id = (int64_t)r.insertId();
            break;
        }
        catch (const DrogonDbException &)
        {
            if (attempt >= 3) throw;
            slug = base + "-" + util::randomHex(4);  // slug collision with a concurrent request
        }
    }
    co_await db->execSqlCoro(kInsertTags, id, tagsJson);
    auto out = co_await fetchArticle(v, slug);
    co_return jsonResp(*out, k201Created);
}

Task<Resp> updateArticle(HttpRequestPtr req, std::string slug)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro(
        "SELECT id, title, description, body, author_id FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    if (cur[0]["author_id"].as<int64_t>() != v.id)
        co_return errorResp(k403Forbidden, "article", "forbidden");
    auto a = bodyObject(req, "article");
    if (!a) co_return badBody("article");

    Errors e;
    std::string title = cur[0]["title"].as<std::string>();
    std::string description = cur[0]["description"].as<std::string>();
    std::string body = cur[0]["body"].as<std::string>();
    for (const char *k : {"title", "description", "body"})
    {
        if (!a->isMember(k)) continue;
        if (blankField(*a, k))
        {
            e.add(k, "can't be blank");
            continue;
        }
        (std::string(k) == "title" ? title : std::string(k) == "body" ? body : description) =
            (*a)[k].asString();
    }
    std::optional<std::string> tagsJson;
    if (a->isMember("tagList"))
    {
        tagsJson = tagsToJson((*a)["tagList"]);
        if (!tagsJson) e.add("tagList", "is invalid");
    }
    if (e.any) co_return e.resp();

    int64_t id = cur[0]["id"].as<int64_t>();
    co_await db->execSqlCoro(
        "UPDATE articles SET title = ?, description = ?, body = ?, updated_at = ? WHERE id = ?",
        title, description, body, util::nowIso(), id);
    if (tagsJson)
    {
        co_await db->execSqlCoro("DELETE FROM article_tags WHERE article_id = ?", id);
        co_await db->execSqlCoro(kInsertTags, id, *tagsJson);
    }
    auto out = co_await fetchArticle(v, slug);
    co_return jsonResp(*out);
}

Task<Resp> deleteArticle(HttpRequestPtr req, std::string slug)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT id, author_id FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    if (cur[0]["author_id"].as<int64_t>() != v.id)
        co_return errorResp(k403Forbidden, "article", "forbidden");
    co_await db->execSqlCoro("DELETE FROM articles WHERE id = ?", cur[0]["id"].as<int64_t>());
    co_return noContent();
}

Task<Resp> setFavorite(HttpRequestPtr req, std::string slug, bool favorite)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto cur = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (cur.empty()) co_return errorResp(k404NotFound, "article", "not found");
    int64_t id = cur[0]["id"].as<int64_t>();
    if (favorite)
        co_await db->execSqlCoro(
            "INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)", v.id, id);
    else
        co_await db->execSqlCoro("DELETE FROM favorites WHERE user_id = ? AND article_id = ?",
                                 v.id, id);
    auto out = co_await fetchArticle(v, slug);
    co_return jsonResp(*out);
}

Task<Resp> favoriteHandler(HttpRequestPtr req, std::string slug)
{
    co_return co_await setFavorite(req, slug, true);
}

Task<Resp> unfavoriteHandler(HttpRequestPtr req, std::string slug)
{
    co_return co_await setFavorite(req, slug, false);
}

// ----- comments -----
Json::Value commentJson(const Row &row)
{
    Json::Value c;
    c["id"] = (Json::Int64)row["id"].as<int64_t>();
    c["createdAt"] = row["created_at"].as<std::string>();
    c["updatedAt"] = row["updated_at"].as<std::string>();
    c["body"] = row["body"].as<std::string>();
    c["author"] = profileJson(row, row["fol"].as<int>() != 0);
    return c;
}

const char *kCommentSelect =
    "SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image, "
    "EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followed_id = c.author_id) "
    "AS fol FROM comments c JOIN users u ON u.id = c.author_id ";

Task<Resp> addComment(HttpRequestPtr req, std::string slug)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto art = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (art.empty()) co_return errorResp(k404NotFound, "article", "not found");
    auto c = bodyObject(req, "comment");
    if (!c) co_return badBody("body");
    if (blankField(*c, "body")) co_return errorResp(k422UnprocessableEntity, "body", "can't be blank");
    std::string now = util::nowIso();
    auto r = co_await db->execSqlCoro(
        "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) "
        "VALUES (?, ?, ?, ?, ?)",
        art[0]["id"].as<int64_t>(), v.id, (*c)["body"].asString(), now, now);
    auto row = co_await db->execSqlCoro(std::string(kCommentSelect) + "WHERE c.id = ?", v.id,
                                        (int64_t)r.insertId());
    Json::Value out;
    out["comment"] = commentJson(row[0]);
    co_return jsonResp(out, k201Created);
}

Task<Resp> listComments(HttpRequestPtr req, std::string slug)
{
    auto v = co_await authenticate(req, false);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto art = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (art.empty()) co_return errorResp(k404NotFound, "article", "not found");
    auto rows = co_await db->execSqlCoro(
        std::string(kCommentSelect) + "WHERE c.article_id = ? ORDER BY c.id ASC", v.id,
        art[0]["id"].as<int64_t>());
    Json::Value out;
    out["comments"] = Json::Value(Json::arrayValue);
    for (auto &r : rows) out["comments"].append(commentJson(r));
    co_return jsonResp(out);
}

Task<Resp> deleteComment(HttpRequestPtr req, std::string slug, std::string idStr)
{
    auto v = co_await authenticate(req, true);
    if (v.error) co_return v.error;
    auto db = app().getDbClient();
    auto art = co_await db->execSqlCoro("SELECT id FROM articles WHERE slug = ?", slug);
    if (art.empty()) co_return errorResp(k404NotFound, "article", "not found");
    int64_t cid = -1;
    try
    {
        size_t pos = 0;
        cid = std::stoll(idStr, &pos);
        if (pos != idStr.size()) cid = -1;
    }
    catch (...)
    {
    }
    auto c = co_await db->execSqlCoro(
        "SELECT id, author_id FROM comments WHERE id = ? AND article_id = ?", cid,
        art[0]["id"].as<int64_t>());
    if (c.empty()) co_return errorResp(k404NotFound, "comment", "not found");
    if (c[0]["author_id"].as<int64_t>() != v.id)
        co_return errorResp(k403Forbidden, "comment", "forbidden");
    co_await db->execSqlCoro("DELETE FROM comments WHERE id = ?", cid);
    co_return noContent();
}

// ----- tags -----
Task<Resp> listTags(HttpRequestPtr)
{
    auto db = app().getDbClient();
    auto rows = co_await db->execSqlCoro(
        "SELECT tag FROM article_tags GROUP BY tag ORDER BY COUNT(*) DESC, tag ASC");
    Json::Value out;
    out["tags"] = Json::Value(Json::arrayValue);
    for (auto &r : rows) out["tags"].append(r["tag"].as<std::string>());
    co_return jsonResp(out);
}

// Wraps a coroutine handler so unexpected exceptions become a JSON 500.
template <typename... Args>
auto safe(Task<Resp> (*fn)(HttpRequestPtr, Args...))
{
    return [fn](HttpRequestPtr req, Args... args) -> Task<Resp> {
        try
        {
            co_return co_await fn(std::move(req), std::move(args)...);
        }
        catch (const std::exception &ex)
        {
            LOG_ERROR << "request failed: " << ex.what();
            co_return errorResp(k500InternalServerError, "server", "internal error");
        }
    };
}
}  // namespace

void registerRoutes()
{
    auto &a = app();
    a.registerHandler("/api/users", safe(registerUser), {Post});
    a.registerHandler("/api/users/login", safe(login), {Post});
    a.registerHandler("/api/user", safe(currentUser), {Get});
    a.registerHandler("/api/user", safe(updateUser), {Put});

    a.registerHandler("/api/profiles/{username}", safe(getProfile), {Get});
    a.registerHandler("/api/profiles/{username}/follow", safe(followUser), {Post});
    a.registerHandler("/api/profiles/{username}/follow", safe(unfollowUser), {Delete});

    a.registerHandler("/api/articles", safe(listArticlesHandler), {Get});
    a.registerHandler("/api/articles", safe(createArticle), {Post});
    a.registerHandler("/api/articles/feed", safe(feedHandler), {Get});
    a.registerHandler("/api/articles/{slug}", safe(getArticle), {Get});
    a.registerHandler("/api/articles/{slug}", safe(updateArticle), {Put});
    a.registerHandler("/api/articles/{slug}", safe(deleteArticle), {Delete});
    a.registerHandler("/api/articles/{slug}/favorite", safe(favoriteHandler), {Post});
    a.registerHandler("/api/articles/{slug}/favorite", safe(unfavoriteHandler), {Delete});
    a.registerHandler("/api/articles/{slug}/comments", safe(addComment), {Post});
    a.registerHandler("/api/articles/{slug}/comments", safe(listComments), {Get});
    a.registerHandler("/api/articles/{slug}/comments/{id}", safe(deleteComment), {Delete});

    a.registerHandler("/api/tags", safe(listTags), {Get});
}
