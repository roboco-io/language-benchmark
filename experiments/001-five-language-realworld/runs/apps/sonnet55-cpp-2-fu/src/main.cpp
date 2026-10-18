// RealWorld ("Conduit") backend API on Drogon + SQLite.
#include <drogon/drogon.h>
#include <drogon/orm/DbClient.h>

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <map>
#include <thread>

#include "util.h"

using namespace drogon;
using namespace drogon::orm;
using rw::errorResponse;
using rw::jsonResponse;

namespace
{
DbClientPtr g_db;

using Errors = std::map<std::string, std::vector<std::string>>;
using Resp = HttpResponsePtr;

Resp validationError(const Errors &errs, HttpStatusCode code = k422UnprocessableEntity)
{
    Json::Value e(Json::objectValue);
    for (auto &[field, msgs] : errs)
        for (auto &m : msgs)
            e["errors"][field].append(m);
    return jsonResponse(e, code);
}

// ---------- request helpers ----------

// Authenticates the request. Returns nullptr on success and sets `uid`
// (0 for anonymous when auth is optional); otherwise returns the error.
Resp authenticate(const HttpRequestPtr &req, int64_t &uid, bool required)
{
    uid = 0;
    std::string h = req->getHeader("Authorization");
    std::string token;
    if (h.rfind("Token ", 0) == 0)
        token = h.substr(6);
    else if (h.rfind("Bearer ", 0) == 0)
        token = h.substr(7);
    token = rw::trim(token);
    if (token.empty())
    {
        if (required)
            return errorResponse(k401Unauthorized, "token", "is missing");
        return nullptr;
    }
    uid = rw::verifyToken(token);
    if (uid == 0)
        return errorResponse(k401Unauthorized, "token", "is invalid");
    return nullptr;
}

// Returns the JSON object stored under `key` in the request body.
const Json::Value *bodyObject(const HttpRequestPtr &req, const char *key)
{
    auto j = req->getJsonObject();
    if (!j || !j->isObject() || !(*j)[key].isObject())
        return nullptr;
    return &(*j)[key];
}

bool isBlank(const Json::Value &o, const char *name)
{
    return !o.isMember(name) || !o[name].isString() ||
           rw::trim(o[name].asString()).empty();
}

int64_t queryInt(const HttpRequestPtr &req, const std::string &name, int64_t def, int64_t lo, int64_t hi)
{
    std::string v = req->getParameter(name);
    if (v.empty())
        return def;
    char *end = nullptr;
    long long n = std::strtoll(v.c_str(), &end, 10);
    if (end == v.c_str())
        return def;
    return std::clamp<int64_t>(n, lo, hi);
}

Json::Value nullableString(const Row &row, const char *col)
{
    auto f = row[col];
    if (f.isNull())
        return Json::nullValue;
    return f.as<std::string>();
}

Json::Value userJson(const Row &row)
{
    Json::Value u(Json::objectValue);
    u["email"] = row["email"].as<std::string>();
    u["token"] = rw::makeToken(row["id"].as<int64_t>());
    u["username"] = row["username"].as<std::string>();
    u["bio"] = nullableString(row, "bio");
    u["image"] = nullableString(row, "image");
    Json::Value out(Json::objectValue);
    out["user"] = u;
    return out;
}

bool isUniqueViolation(const SqlError &e)
{
    return std::string(e.what()).find("UNIQUE") != std::string::npos;
}

// ---------- article queries ----------

struct ArticleFilter
{
    std::string slug, tag, author, favorited;
    bool feed = false;
    int64_t viewer = 0;
    int64_t limit = 20, offset = 0;
};

const char *kArticleWhere =
    " WHERE (?1 = '' OR a.slug = ?1)"
    " AND (?2 = '' OR EXISTS (SELECT 1 FROM json_each(a.tag_list) j WHERE j.value = ?2))"
    " AND (?3 = '' OR u.username = ?3)"
    " AND (?4 = '' OR EXISTS (SELECT 1 FROM favorites f2 JOIN users u2 ON u2.id = f2.user_id"
    "      WHERE f2.article_id = a.id AND u2.username = ?4))"
    " AND (?5 = 0 OR a.author_id IN (SELECT followed_id FROM follows WHERE follower_id = ?6))";

Json::Value parseTagColumn(const std::string &text)
{
    Json::Value v;
    Json::CharReaderBuilder rb;
    std::string errs;
    std::unique_ptr<Json::CharReader> reader(rb.newCharReader());
    if (!reader->parse(text.data(), text.data() + text.size(), &v, &errs) || !v.isArray())
        return Json::Value(Json::arrayValue);
    return v;
}

std::string tagColumn(const std::vector<std::string> &tags)
{
    Json::Value arr(Json::arrayValue);
    for (auto &t : tags)
        arr.append(t);
    Json::StreamWriterBuilder wb;
    wb["indentation"] = "";
    return Json::writeString(wb, arr);
}

Task<Json::Value> loadArticles(const ArticleFilter &f, bool withBody, int64_t *total = nullptr)
{
    std::string sql =
        "SELECT a.id, a.slug, a.title, a.description, a.body, a.tag_list, a.created_at, a.updated_at,"
        " u.username, u.bio, u.image,"
        " (SELECT COUNT(*) FROM favorites x WHERE x.article_id = a.id) AS fc,"
        " EXISTS (SELECT 1 FROM favorites x WHERE x.article_id = a.id AND x.user_id = ?6) AS fav,"
        " EXISTS (SELECT 1 FROM follows w WHERE w.follower_id = ?6 AND w.followed_id = a.author_id) AS fol"
        " FROM articles a JOIN users u ON u.id = a.author_id" +
        std::string(kArticleWhere) + " ORDER BY a.id DESC LIMIT ?7 OFFSET ?8";
    int64_t feed = f.feed ? 1 : 0;
    auto rows = co_await g_db->execSqlCoro(sql, f.slug, f.tag, f.author, f.favorited, feed, f.viewer, f.limit, f.offset);

    if (total)
    {
        std::string csql = "SELECT COUNT(*) AS c FROM articles a JOIN users u ON u.id = a.author_id" +
                           std::string(kArticleWhere);
        auto cr = co_await g_db->execSqlCoro(csql, f.slug, f.tag, f.author, f.favorited, feed, f.viewer);
        *total = cr[0]["c"].as<int64_t>();
    }

    Json::Value list(Json::arrayValue);
    for (auto &r : rows)
    {
        Json::Value a(Json::objectValue);
        a["slug"] = r["slug"].as<std::string>();
        a["title"] = r["title"].as<std::string>();
        a["description"] = r["description"].as<std::string>();
        if (withBody)
            a["body"] = r["body"].as<std::string>();
        a["tagList"] = parseTagColumn(r["tag_list"].as<std::string>());
        a["createdAt"] = r["created_at"].as<std::string>();
        a["updatedAt"] = r["updated_at"].as<std::string>();
        a["favorited"] = r["fav"].as<int>() != 0;
        a["favoritesCount"] = static_cast<Json::Int64>(r["fc"].as<int64_t>());
        Json::Value au(Json::objectValue);
        au["username"] = r["username"].as<std::string>();
        au["bio"] = nullableString(r, "bio");
        au["image"] = nullableString(r, "image");
        au["following"] = r["fol"].as<int>() != 0;
        a["author"] = au;
        list.append(a);
    }
    co_return list;
}

Task<Resp> singleArticleResponse(const std::string &slug, int64_t viewer, HttpStatusCode code = k200OK)
{
    ArticleFilter f;
    f.slug = slug;
    f.viewer = viewer;
    auto list = co_await loadArticles(f, true);
    if (list.empty())
        co_return errorResponse(k404NotFound, "article", "not found");
    Json::Value out(Json::objectValue);
    out["article"] = list[0u];
    co_return jsonResponse(out, code);
}

Task<Resp> listArticles(const HttpRequestPtr &req, bool feed)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, feed))
        co_return err;
    ArticleFilter f;
    f.viewer = uid;
    f.feed = feed;
    if (!feed)
    {
        f.tag = req->getParameter("tag");
        f.author = req->getParameter("author");
        f.favorited = req->getParameter("favorited");
    }
    f.limit = queryInt(req, "limit", 20, 0, 1000);
    f.offset = queryInt(req, "offset", 0, 0, INT64_MAX);
    int64_t total = 0;
    auto list = co_await loadArticles(f, false, &total);
    Json::Value out(Json::objectValue);
    out["articles"] = list;
    out["articlesCount"] = static_cast<Json::Int64>(total);
    co_return jsonResponse(out);
}

// Looks up an article id/author by slug. Returns an empty result if missing.

struct ArticleRef
{
    bool found = false;
    int64_t id = 0, authorId = 0;
    std::string slug, title, description, body, tagList;
};

Task<ArticleRef> findArticle(const std::string &slug)
{
    ArticleRef a;
    auto r = co_await g_db->execSqlCoro(
        "SELECT id, author_id, slug, title, description, body, tag_list FROM articles WHERE slug = ?", slug);
    if (!r.empty())
    {
        a.found = true;
        a.id = r[0]["id"].as<int64_t>();
        a.authorId = r[0]["author_id"].as<int64_t>();
        a.slug = r[0]["slug"].as<std::string>();
        a.title = r[0]["title"].as<std::string>();
        a.description = r[0]["description"].as<std::string>();
        a.body = r[0]["body"].as<std::string>();
        a.tagList = r[0]["tag_list"].as<std::string>();
    }
    co_return a;
}

// Reads tagList (array of strings) into an ordered, de-duplicated list.
std::vector<std::string> parseTags(const Json::Value &arr)
{
    std::vector<std::string> out;
    for (auto &t : arr)
    {
        if (!t.isString())
            continue;
        std::string s = rw::trim(t.asString());
        if (!s.empty() && std::find(out.begin(), out.end(), s) == out.end())
            out.push_back(s);
    }
    return out;
}

Task<std::string> uniqueSlug(const std::string &title, int64_t excludeId)
{
    std::string base = rw::slugify(title);
    std::string slug = base;
    for (int i = 0; i < 20; ++i)
    {
        auto r = co_await g_db->execSqlCoro("SELECT 1 FROM articles WHERE slug = ? AND id != ?", slug, excludeId);
        if (r.empty())
            co_return slug;
        slug = base + "-" + rw::randomHex(3);
    }
    co_return base + "-" + rw::randomHex(8);
}

// ---------- users ----------

Task<Resp> registerUser(HttpRequestPtr req)
{
    auto u = bodyObject(req, "user");
    if (!u)
        co_return errorResponse(k422UnprocessableEntity, "user", "can't be blank");
    Errors errs;
    if (isBlank(*u, "username"))
        errs["username"].push_back("can't be blank");
    if (isBlank(*u, "email"))
        errs["email"].push_back("can't be blank");
    else if (rw::trim((*u)["email"].asString()).find('@') == std::string::npos)
        errs["email"].push_back("is invalid");
    if (isBlank(*u, "password"))
        errs["password"].push_back("can't be blank");
    else if ((*u)["password"].asString().size() < 8)
        errs["password"].push_back("is too short (minimum is 8 characters)");
    if (!errs.empty())
        co_return validationError(errs);

    std::string username = rw::trim((*u)["username"].asString());
    std::string email = rw::trim((*u)["email"].asString());
    auto dup = co_await g_db->execSqlCoro("SELECT username, email FROM users WHERE username = ? OR email = ?", username, email);
    for (auto &r : dup)
    {
        if (r["username"].as<std::string>() == username)
            errs["username"] = {"has already been taken"};
        if (r["email"].as<std::string>() == email)
            errs["email"] = {"has already been taken"};
    }
    if (!errs.empty())
        co_return validationError(errs, k409Conflict);

    std::string hash = rw::hashPassword((*u)["password"].asString());
    int64_t id;
    try
    {
        auto r = co_await g_db->execSqlCoro("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)", username, email, hash);
        id = static_cast<int64_t>(r.insertId());
    }
    catch (const SqlError &e)
    {
        if (isUniqueViolation(e))
            co_return errorResponse(k409Conflict, "user", "has already been taken");
        throw;
    }
    auto row = co_await g_db->execSqlCoro("SELECT * FROM users WHERE id = ?", id);
    co_return jsonResponse(userJson(row[0]), k201Created);
}

Task<Resp> login(HttpRequestPtr req)
{
    auto u = bodyObject(req, "user");
    if (!u)
        co_return errorResponse(k422UnprocessableEntity, "user", "can't be blank");
    Errors errs;
    if (isBlank(*u, "email"))
        errs["email"].push_back("can't be blank");
    if (isBlank(*u, "password"))
        errs["password"].push_back("can't be blank");
    if (!errs.empty())
        co_return validationError(errs);
    auto r = co_await g_db->execSqlCoro("SELECT * FROM users WHERE email = ?", rw::trim((*u)["email"].asString()));
    if (r.empty() || !rw::verifyPassword((*u)["password"].asString(), r[0]["password_hash"].as<std::string>()))
        co_return errorResponse(k401Unauthorized, "credentials", "invalid");
    co_return jsonResponse(userJson(r[0]));
}

Task<Resp> currentUser(HttpRequestPtr req)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto r = co_await g_db->execSqlCoro("SELECT * FROM users WHERE id = ?", uid);
    if (r.empty())
        co_return errorResponse(k401Unauthorized, "token", "is invalid");
    co_return jsonResponse(userJson(r[0]));
}

Task<Resp> updateUser(HttpRequestPtr req)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto u = bodyObject(req, "user");
    if (!u)
        co_return errorResponse(k422UnprocessableEntity, "user", "can't be blank");
    auto cur = co_await g_db->execSqlCoro("SELECT * FROM users WHERE id = ?", uid);
    if (cur.empty())
        co_return errorResponse(k401Unauthorized, "token", "is invalid");

    Errors errs;
    std::string username = cur[0]["username"].as<std::string>();
    std::string email = cur[0]["email"].as<std::string>();
    std::string hash = cur[0]["password_hash"].as<std::string>();
    std::optional<std::string> bio, image;
    bool setBio = false, setImage = false;
    if (cur[0]["bio"].isNull() == false)
        bio = cur[0]["bio"].as<std::string>();
    if (cur[0]["image"].isNull() == false)
        image = cur[0]["image"].as<std::string>();

    if (u->isMember("username"))
    {
        if (isBlank(*u, "username"))
            errs["username"].push_back("can't be blank");
        else
            username = rw::trim((*u)["username"].asString());
    }
    if (u->isMember("email"))
    {
        if (isBlank(*u, "email"))
            errs["email"].push_back("can't be blank");
        else if (rw::trim((*u)["email"].asString()).find('@') == std::string::npos)
            errs["email"].push_back("is invalid");
        else
            email = rw::trim((*u)["email"].asString());
    }
    if (u->isMember("password"))
    {
        if (isBlank(*u, "password"))
            errs["password"].push_back("can't be blank");
        else if ((*u)["password"].asString().size() < 8)
            errs["password"].push_back("is too short (minimum is 8 characters)");
        else
            hash = rw::hashPassword((*u)["password"].asString());
    }
    for (auto [name, target, flag] : {std::tuple<const char *, std::optional<std::string> *, bool *>{"bio", &bio, &setBio},
                                      {"image", &image, &setImage}})
    {
        if (!u->isMember(name))
            continue;
        const Json::Value &v = (*u)[name];
        *flag = true;
        if (v.isNull() || (v.isString() && rw::trim(v.asString()).empty()))
            target->reset();
        else if (v.isString())
            *target = v.asString();
        else
            errs[name].push_back("is invalid");
    }
    if (!errs.empty())
        co_return validationError(errs);

    auto dup = co_await g_db->execSqlCoro("SELECT username, email FROM users WHERE (username = ? OR email = ?) AND id != ?", username, email, uid);
    for (auto &r : dup)
    {
        if (r["username"].as<std::string>() == username)
            errs["username"] = {"has already been taken"};
        if (r["email"].as<std::string>() == email)
            errs["email"] = {"has already been taken"};
    }
    if (!errs.empty())
        co_return validationError(errs, k409Conflict);

    try
    {
        co_await g_db->execSqlCoro(
            "UPDATE users SET username = ?, email = ?, password_hash = ?, bio = ?, image = ? WHERE id = ?",
            username, email, hash, bio, image, uid);
    }
    catch (const SqlError &e)
    {
        if (isUniqueViolation(e))
            co_return errorResponse(k409Conflict, "user", "has already been taken");
        throw;
    }
    auto r = co_await g_db->execSqlCoro("SELECT * FROM users WHERE id = ?", uid);
    co_return jsonResponse(userJson(r[0]));
}

// ---------- profiles ----------

Task<Resp> profileResponse(const std::string &username, int64_t viewer)
{
    auto r = co_await g_db->execSqlCoro(
        "SELECT u.username, u.bio, u.image,"
        " EXISTS (SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followed_id = u.id) AS fol"
        " FROM users u WHERE u.username = ?",
        viewer, username);
    if (r.empty())
        co_return errorResponse(k404NotFound, "profile", "not found");
    Json::Value p(Json::objectValue);
    p["username"] = r[0]["username"].as<std::string>();
    p["bio"] = nullableString(r[0], "bio");
    p["image"] = nullableString(r[0], "image");
    p["following"] = r[0]["fol"].as<int>() != 0;
    Json::Value out(Json::objectValue);
    out["profile"] = p;
    co_return jsonResponse(out);
}

Task<Resp> getProfile(HttpRequestPtr req, std::string username)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, false))
        co_return err;
    co_return co_await profileResponse(username, uid);
}

Task<Resp> follow(HttpRequestPtr req, std::string username, bool doFollow)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto t = co_await g_db->execSqlCoro("SELECT id FROM users WHERE username = ?", username);
    if (t.empty())
        co_return errorResponse(k404NotFound, "profile", "not found");
    int64_t target = t[0]["id"].as<int64_t>();
    if (doFollow)
        co_await g_db->execSqlCoro("INSERT OR IGNORE INTO follows (follower_id, followed_id) VALUES (?, ?)", uid, target);
    else
        co_await g_db->execSqlCoro("DELETE FROM follows WHERE follower_id = ? AND followed_id = ?", uid, target);
    co_return co_await profileResponse(username, uid);
}

// ---------- articles ----------

Task<Resp> createArticle(HttpRequestPtr req)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto a = bodyObject(req, "article");
    if (!a)
        co_return errorResponse(k422UnprocessableEntity, "article", "can't be blank");
    Errors errs;
    for (const char *f : {"title", "description", "body"})
        if (isBlank(*a, f))
            errs[f].push_back("can't be blank");
    if (a->isMember("tagList") && !(*a)["tagList"].isArray() && !(*a)["tagList"].isNull())
        errs["tagList"].push_back("is invalid");
    if (!errs.empty())
        co_return validationError(errs);

    std::string title = rw::trim((*a)["title"].asString());
    std::string tagsJson = tagColumn(parseTags((*a)["tagList"]));
    std::string slug = co_await uniqueSlug(title, 0);
    std::string now = rw::nowIso();

    co_await g_db->execSqlCoro(
        "INSERT INTO articles (slug, title, description, body, tag_list, author_id, created_at, updated_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        slug, title, (*a)["description"].asString(), (*a)["body"].asString(), tagsJson, uid, now, now);
    co_return co_await singleArticleResponse(slug, uid, k201Created);
}

Task<Resp> getArticle(HttpRequestPtr req, std::string slug)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, false))
        co_return err;
    co_return co_await singleArticleResponse(slug, uid);
}

Task<Resp> updateArticle(HttpRequestPtr req, std::string slug)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto cur = co_await findArticle(slug);
    if (!cur.found)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (cur.authorId != uid)
        co_return errorResponse(k403Forbidden, "article", "forbidden");
    auto a = bodyObject(req, "article");
    if (!a)
        co_return errorResponse(k422UnprocessableEntity, "article", "can't be blank");

    Errors errs;
    for (const char *f : {"title", "description", "body"})
        if (a->isMember(f) && isBlank(*a, f))
            errs[f].push_back("can't be blank");
    bool setTags = a->isMember("tagList");
    if (setTags && !(*a)["tagList"].isArray())
        errs["tagList"].push_back(((*a)["tagList"].isNull()) ? "can't be blank" : "is invalid");
    if (!errs.empty())
        co_return validationError(errs);

    std::string title = cur.title, desc = cur.description, body = cur.body, newSlug = cur.slug;
    if (a->isMember("title"))
    {
        title = rw::trim((*a)["title"].asString());
        if (title != cur.title)
            newSlug = co_await uniqueSlug(title, cur.id);
    }
    if (a->isMember("description"))
        desc = (*a)["description"].asString();
    if (a->isMember("body"))
        body = (*a)["body"].asString();

    std::string tagsJson = setTags ? tagColumn(parseTags((*a)["tagList"])) : cur.tagList;
    co_await g_db->execSqlCoro(
        "UPDATE articles SET slug = ?, title = ?, description = ?, body = ?, tag_list = ?, updated_at = ? WHERE id = ?",
        newSlug, title, desc, body, tagsJson, rw::nowIso(), cur.id);
    co_return co_await singleArticleResponse(newSlug, uid);
}

Task<Resp> deleteArticle(HttpRequestPtr req, std::string slug)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto cur = co_await findArticle(slug);
    if (!cur.found)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (cur.authorId != uid)
        co_return errorResponse(k403Forbidden, "article", "forbidden");
    co_await g_db->execSqlCoro("DELETE FROM articles WHERE id = ?", cur.id);
    auto resp = HttpResponse::newHttpResponse();
    resp->setStatusCode(k204NoContent);
    co_return resp;
}

Task<Resp> favorite(HttpRequestPtr req, std::string slug, bool doFav)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto cur = co_await findArticle(slug);
    if (!cur.found)
        co_return errorResponse(k404NotFound, "article", "not found");
    if (doFav)
        co_await g_db->execSqlCoro("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)", uid, cur.id);
    else
        co_await g_db->execSqlCoro("DELETE FROM favorites WHERE user_id = ? AND article_id = ?", uid, cur.id);
    co_return co_await singleArticleResponse(slug, uid);
}

// ---------- comments ----------

Json::Value commentJson(const Row &r, bool following)
{
    Json::Value c(Json::objectValue);
    c["id"] = static_cast<Json::Int64>(r["id"].as<int64_t>());
    c["createdAt"] = r["created_at"].as<std::string>();
    c["updatedAt"] = r["updated_at"].as<std::string>();
    c["body"] = r["body"].as<std::string>();
    Json::Value au(Json::objectValue);
    au["username"] = r["username"].as<std::string>();
    au["bio"] = nullableString(r, "bio");
    au["image"] = nullableString(r, "image");
    au["following"] = following;
    c["author"] = au;
    return c;
}

const char *kCommentSelect =
    "SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,"
    " EXISTS (SELECT 1 FROM follows w WHERE w.follower_id = ?1 AND w.followed_id = c.author_id) AS fol"
    " FROM comments c JOIN users u ON u.id = c.author_id";

Task<Resp> addComment(HttpRequestPtr req, std::string slug)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto cur = co_await findArticle(slug);
    if (!cur.found)
        co_return errorResponse(k404NotFound, "article", "not found");
    auto c = bodyObject(req, "comment");
    if (!c || isBlank(*c, "body"))
        co_return errorResponse(k422UnprocessableEntity, "body", "can't be blank");
    std::string now = rw::nowIso();
    auto ins = co_await g_db->execSqlCoro(
        "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
        cur.id, uid, (*c)["body"].asString(), now, now);
    auto r = co_await g_db->execSqlCoro(std::string(kCommentSelect) + " WHERE c.id = ?2", uid,
                                        static_cast<int64_t>(ins.insertId()));
    Json::Value out(Json::objectValue);
    out["comment"] = commentJson(r[0], r[0]["fol"].as<int>() != 0);
    co_return jsonResponse(out, k201Created);
}

Task<Resp> listComments(HttpRequestPtr req, std::string slug)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, false))
        co_return err;
    auto cur = co_await findArticle(slug);
    if (!cur.found)
        co_return errorResponse(k404NotFound, "article", "not found");
    auto rows = co_await g_db->execSqlCoro(std::string(kCommentSelect) + " WHERE c.article_id = ?2 ORDER BY c.id", uid, cur.id);
    Json::Value list(Json::arrayValue);
    for (auto &r : rows)
        list.append(commentJson(r, r["fol"].as<int>() != 0));
    Json::Value out(Json::objectValue);
    out["comments"] = list;
    co_return jsonResponse(out);
}

Task<Resp> updateComment(HttpRequestPtr req, std::string slug, std::string idStr)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto cur = co_await findArticle(slug);
    if (!cur.found)
        co_return errorResponse(k404NotFound, "article", "not found");
    char *end = nullptr;
    long long cid = std::strtoll(idStr.c_str(), &end, 10);
    if (idStr.empty() || *end != '\0')
        co_return errorResponse(k404NotFound, "comment", "not found");
    auto r = co_await g_db->execSqlCoro("SELECT author_id FROM comments WHERE id = ? AND article_id = ?", static_cast<int64_t>(cid), cur.id);
    if (r.empty())
        co_return errorResponse(k404NotFound, "comment", "not found");
    if (r[0]["author_id"].as<int64_t>() != uid)
        co_return errorResponse(k403Forbidden, "comment", "forbidden");
    auto c = bodyObject(req, "comment");
    if (!c || isBlank(*c, "body"))
        co_return errorResponse(k422UnprocessableEntity, "body", "can't be blank");
    co_await g_db->execSqlCoro("UPDATE comments SET body = ?, updated_at = ? WHERE id = ?", (*c)["body"].asString(),
                               rw::nowIso(), static_cast<int64_t>(cid));
    auto row = co_await g_db->execSqlCoro(std::string(kCommentSelect) + " WHERE c.id = ?2", uid, static_cast<int64_t>(cid));
    Json::Value out(Json::objectValue);
    out["comment"] = commentJson(row[0], row[0]["fol"].as<int>() != 0);
    co_return jsonResponse(out);
}

Task<Resp> deleteComment(HttpRequestPtr req, std::string slug, std::string idStr)
{
    int64_t uid;
    if (auto err = authenticate(req, uid, true))
        co_return err;
    auto cur = co_await findArticle(slug);
    if (!cur.found)
        co_return errorResponse(k404NotFound, "article", "not found");
    char *end = nullptr;
    long long cid = std::strtoll(idStr.c_str(), &end, 10);
    if (idStr.empty() || *end != '\0')
        co_return errorResponse(k404NotFound, "comment", "not found");
    auto r = co_await g_db->execSqlCoro("SELECT author_id FROM comments WHERE id = ? AND article_id = ?", static_cast<int64_t>(cid), cur.id);
    if (r.empty())
        co_return errorResponse(k404NotFound, "comment", "not found");
    if (r[0]["author_id"].as<int64_t>() != uid)
        co_return errorResponse(k403Forbidden, "comment", "forbidden");
    co_await g_db->execSqlCoro("DELETE FROM comments WHERE id = ?", static_cast<int64_t>(cid));
    auto resp = HttpResponse::newHttpResponse();
    resp->setStatusCode(k204NoContent);
    co_return resp;
}

Task<Resp> listTags(HttpRequestPtr)
{
    auto rows = co_await g_db->execSqlCoro("SELECT j.value AS tag FROM articles a, json_each(a.tag_list) j GROUP BY j.value ORDER BY MIN(a.id), MIN(j.key)");
    Json::Value list(Json::arrayValue);
    for (auto &r : rows)
        list.append(r["tag"].as<std::string>());
    Json::Value out(Json::objectValue);
    out["tags"] = list;
    co_return jsonResponse(out);
}

// ---------- routing ----------

// Wraps a coroutine handler so that unexpected exceptions become a 500.
template <typename... Args>
void route(const std::string &path, HttpMethod method, Task<Resp> (*h)(HttpRequestPtr, Args...))
{
    app().registerHandler(
        path,
        [h](HttpRequestPtr req, Args... args) -> Task<Resp> {
            try
            {
                co_return co_await h(std::move(req), std::move(args)...);
            }
            catch (const std::exception &e)
            {
                LOG_ERROR << "Unhandled error: " << e.what();
                co_return errorResponse(k500InternalServerError, "server", "internal error");
            }
        },
        {method});
}

const char *kSchema[] = {
    "CREATE TABLE IF NOT EXISTS users ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " username TEXT NOT NULL UNIQUE,"
    " email TEXT NOT NULL UNIQUE,"
    " password_hash TEXT NOT NULL,"
    " bio TEXT, image TEXT)",
    "CREATE TABLE IF NOT EXISTS articles ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " slug TEXT NOT NULL UNIQUE,"
    " title TEXT NOT NULL,"
    " description TEXT NOT NULL,"
    " body TEXT NOT NULL,"
    " tag_list TEXT NOT NULL DEFAULT '[]',"
    " author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " created_at TEXT NOT NULL,"
    " updated_at TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id)",
    "CREATE TABLE IF NOT EXISTS favorites ("
    " user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " PRIMARY KEY (user_id, article_id))",
    "CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id)",
    "CREATE TABLE IF NOT EXISTS follows ("
    " follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " followed_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " PRIMARY KEY (follower_id, followed_id))",
    "CREATE TABLE IF NOT EXISTS comments ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " body TEXT NOT NULL,"
    " created_at TEXT NOT NULL,"
    " updated_at TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)",
};
}  // namespace

int main()
{
    const char *portEnv = std::getenv("PORT");
    int port = (portEnv && *portEnv) ? std::atoi(portEnv) : 8000;
    if (port <= 0 || port > 65535)
        port = 8000;
    const char *dbEnv = std::getenv("DB_PATH");
    std::string dbPath = (dbEnv && *dbEnv) ? dbEnv : "data/realworld.db";
    auto parent = std::filesystem::path(dbPath).parent_path();
    if (!parent.empty())
        std::filesystem::create_directories(parent);

    size_t threads = std::max(1u, std::thread::hardware_concurrency());

    const std::vector<std::string> pragmas = {"PRAGMA busy_timeout=5000",
                                              "PRAGMA journal_mode=WAL",
                                              "PRAGMA synchronous=NORMAL",
                                              "PRAGMA foreign_keys=ON"};
    // A single bootstrap connection switches the file to WAL and creates the
    // schema, so the pooled connections never race on the journal-mode change.
    {
        auto boot = DbClient::newSqlite3Client("filename=" + dbPath, 1, pragmas);
        for (auto sql : kSchema)
            boot->execSqlSync(sql);
    }
    // Every pooled connection runs the same pragmas before it is used.
    g_db = DbClient::newSqlite3Client("filename=" + dbPath, threads, pragmas);

    route("/api/users", Post, registerUser);
    route("/api/users/login", Post, login);
    route("/api/user", Get, currentUser);
    route("/api/user", Put, updateUser);
    route("/api/profiles/{username}", Get, getProfile);
    route<std::string>("/api/profiles/{username}/follow", Post, +[](HttpRequestPtr r, std::string u) { return follow(std::move(r), std::move(u), true); });
    route<std::string>("/api/profiles/{username}/follow", Delete, +[](HttpRequestPtr r, std::string u) { return follow(std::move(r), std::move(u), false); });
    route("/api/articles/feed", Get, +[](HttpRequestPtr r) { return listArticles(r, true); });
    route("/api/articles", Get, +[](HttpRequestPtr r) { return listArticles(r, false); });
    route("/api/articles", Post, createArticle);
    route("/api/articles/{slug}", Get, getArticle);
    route("/api/articles/{slug}", Put, updateArticle);
    route("/api/articles/{slug}", Delete, deleteArticle);
    route<std::string>("/api/articles/{slug}/favorite", Post, +[](HttpRequestPtr r, std::string s) { return favorite(std::move(r), std::move(s), true); });
    route<std::string>("/api/articles/{slug}/favorite", Delete, +[](HttpRequestPtr r, std::string s) { return favorite(std::move(r), std::move(s), false); });
    route("/api/articles/{slug}/comments", Post, addComment);
    route("/api/articles/{slug}/comments", Get, listComments);
    route("/api/articles/{slug}/comments/{id}", Put, updateComment);
    route("/api/articles/{slug}/comments/{id}", Delete, deleteComment);
    route("/api/tags", Get, listTags);

    // CORS: answer preflight requests and tag every response.
    app().registerPreRoutingAdvice([](const HttpRequestPtr &req, AdviceCallback &&cb, AdviceChainCallback &&next) {
        if (req->method() == Options)
        {
            auto resp = HttpResponse::newHttpResponse();
            resp->setStatusCode(k204NoContent);
            cb(resp);
            return;
        }
        next();
    });
    app().registerPostHandlingAdvice([](const HttpRequestPtr &, const HttpResponsePtr &resp) {
        resp->addHeader("Access-Control-Allow-Origin", "*");
        resp->addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
        resp->addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    });

    app()
        .setLogLevel(trantor::Logger::kWarn)
        .addListener("0.0.0.0", static_cast<uint16_t>(port))
        .setThreadNum(threads)
        .run();
    return 0;
}
