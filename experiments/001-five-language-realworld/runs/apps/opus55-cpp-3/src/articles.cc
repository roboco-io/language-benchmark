#include <drogon/drogon.h>

#include <unordered_set>
#include <vector>

#include "db.h"
#include "handlers.h"

using namespace drogon;

namespace {

constexpr int64_t kDefaultLimit = 20;
constexpr int64_t kMaxLimit = 1000;

// Selects everything needed to render an article. The two leading parameters
// are the viewer id (favorited, following); body is optional for lists.
std::string articleSelect(bool withBody) {
  return std::string(
             "SELECT a.id, a.slug, a.title, a.description, ") +
         (withBody ? "a.body, " : "") +
         "a.created_at, a.updated_at, u.username, u.bio, u.image,"
         " (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,"
         " EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ?)"
         " AS favorited,"
         " EXISTS(SELECT 1 FROM follows fo WHERE fo.follower_id = ? AND"
         " fo.followee_id = a.author_id) AS following,"
         " (SELECT group_concat(tag, char(31)) FROM (SELECT tag FROM article_tags t"
         " WHERE t.article_id = a.id ORDER BY position)) AS tags"
         " FROM articles a JOIN users u ON u.id = a.author_id";
}

Json::Value articleJson(const orm::Row &row, bool withBody) {
  Json::Value a;
  a["slug"] = row["slug"].as<std::string>();
  a["title"] = row["title"].as<std::string>();
  a["description"] = row["description"].as<std::string>();
  if (withBody) a["body"] = row["body"].as<std::string>();
  Json::Value tags(Json::arrayValue);
  if (!row["tags"].isNull()) {
    std::string all = row["tags"].as<std::string>();
    size_t start = 0;
    while (true) {
      size_t pos = all.find('\x1f', start);
      tags.append(all.substr(start, pos - start));
      if (pos == std::string::npos) break;
      start = pos + 1;
    }
  }
  a["tagList"] = tags;
  a["createdAt"] = row["created_at"].as<std::string>();
  a["updatedAt"] = row["updated_at"].as<std::string>();
  a["favorited"] = row["favorited"].as<int64_t>() != 0;
  a["favoritesCount"] = row["favorites_count"].as<int64_t>();
  Json::Value author;
  author["username"] = row["username"].as<std::string>();
  author["bio"] = nullableString(row["bio"]);
  author["image"] = nullableString(row["image"]);
  author["following"] = row["following"].as<int64_t>() != 0;
  a["author"] = author;
  return a;
}

void sendArticle(const Callback &cb, int64_t articleId, int64_t viewerId,
                 HttpStatusCode code = k200OK) {
  auto rows = db::get()->execSqlSync(articleSelect(true) + " WHERE a.id = ?", viewerId,
                                     viewerId, articleId);
  Json::Value body;
  body["article"] = articleJson(rows[0], true);
  sendJson(cb, body, code);
}

struct ArticleRef {
  int64_t id;
  int64_t authorId;
};

std::optional<ArticleRef> findArticle(const std::string &slug) {
  auto rows = db::get()->execSqlSync("SELECT id, author_id FROM articles WHERE slug = ?", slug);
  if (rows.empty()) return std::nullopt;
  return ArticleRef{rows[0]["id"].as<int64_t>(), rows[0]["author_id"].as<int64_t>()};
}

int64_t queryInt(const HttpRequestPtr &req, const char *name, int64_t def, int64_t max) {
  const std::string &s = req->getParameter(name);
  if (s.empty()) return def;
  try {
    long long v = std::stoll(s);
    if (v < 0) return def;
    return v > max ? max : v;
  } catch (...) {
    return def;
  }
}

// Parses tagList into a de-duplicated ordered list; returns false if invalid.
bool parseTags(const Json::Value &v, std::vector<std::string> &out) {
  if (!v.isArray()) return false;
  std::unordered_set<std::string> seen;
  for (const auto &t : v) {
    if (!t.isString()) return false;
    std::string tag = t.asString();
    if (tag.empty()) continue;
    if (seen.insert(tag).second) out.push_back(tag);
  }
  return true;
}

void writeTags(const orm::DbClientPtr &client, int64_t articleId,
               const std::vector<std::string> &tags) {
  client->execSqlSync("DELETE FROM article_tags WHERE article_id = ?", articleId);
  for (size_t i = 0; i < tags.size(); ++i)
    client->execSqlSync("INSERT INTO article_tags (article_id, tag, position) VALUES (?, ?, ?)",
                        articleId, tags[i], static_cast<int64_t>(i));
}

std::string uniqueSlug(const std::string &title) {
  return slugify(title) + "-" + randomSuffix();
}

void sendList(const Callback &cb, const std::string &where, int64_t viewer,
              const std::vector<std::string> &params, int64_t limit, int64_t offset) {
  // Every filter takes its value twice: "? = '' OR ... = ?".
  auto client = db::get();
  std::string countSql = "SELECT COUNT(*) AS n FROM articles a JOIN users u ON u.id = a.author_id" + where;
  std::string listSql = articleSelect(false) + where +
                        " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?";
  orm::Result countRows(nullptr), rows(nullptr);
  if (params.size() == 3) {
    const auto &t = params[0], &au = params[1], &fv = params[2];
    countRows = client->execSqlSync(countSql, t, t, au, au, fv, fv);
    rows = client->execSqlSync(listSql, viewer, viewer, t, t, au, au, fv, fv, limit, offset);
  } else {
    countRows = client->execSqlSync(countSql, viewer);
    rows = client->execSqlSync(listSql, viewer, viewer, viewer, limit, offset);
  }
  Json::Value body;
  body["articles"] = Json::Value(Json::arrayValue);
  for (const auto &row : rows) body["articles"].append(articleJson(row, false));
  body["articlesCount"] = countRows[0]["n"].as<int64_t>();
  sendJson(cb, body);
}

void listArticles(const HttpRequestPtr &req, Callback &&cb) {
  auto viewer = optionalUser(req, cb);
  if (!viewer) return;
  static const std::string where =
      " WHERE (? = '' OR a.id IN (SELECT article_id FROM article_tags WHERE tag = ?))"
      " AND (? = '' OR u.username = ?)"
      " AND (? = '' OR a.id IN (SELECT f.article_id FROM favorites f JOIN users fu"
      " ON fu.id = f.user_id WHERE fu.username = ?))";
  sendList(cb, where, *viewer,
           {req->getParameter("tag"), req->getParameter("author"),
            req->getParameter("favorited")},
           queryInt(req, "limit", kDefaultLimit, kMaxLimit),
           queryInt(req, "offset", 0, INT64_MAX));
}

void feed(const HttpRequestPtr &req, const Callback &cb) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  static const std::string where =
      " WHERE a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?)";
  sendList(cb, where, *uid, {}, queryInt(req, "limit", kDefaultLimit, kMaxLimit),
           queryInt(req, "offset", 0, INT64_MAX));
}

void getArticle(const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
  if (slug == "feed") return feed(req, cb);
  auto viewer = optionalUser(req, cb);
  if (!viewer) return;
  auto art = findArticle(slug);
  if (!art) return sendError(cb, k404NotFound, "article", "not found");
  sendArticle(cb, art->id, *viewer);
}

void sendValidation(const Callback &cb, const Json::Value &errors) {
  Json::Value body;
  body["errors"] = errors;
  sendJson(cb, body, k422UnprocessableEntity);
}

void createArticle(const HttpRequestPtr &req, Callback &&cb) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto article = requireWrapped(req, cb, "article");
  if (!article) return;
  Json::Value errors;
  for (const char *f : {"title", "description", "body"}) {
    const auto &v = (*article)[f];
    if (!v.isString() || v.asString().empty()) errors[f].append("can't be blank");
  }
  std::vector<std::string> tags;
  if (article->isMember("tagList") && !parseTags((*article)["tagList"], tags))
    errors["tagList"].append("is invalid");
  if (!errors.empty()) return sendValidation(cb, errors);

  auto client = db::get();
  std::string title = (*article)["title"].asString();
  std::string now = nowIso();
  int64_t id = 0;
  for (int attempt = 0;; ++attempt) {
    try {
      auto r = client->execSqlSync(
          "INSERT INTO articles (slug, title, description, body, author_id, created_at, "
          "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          uniqueSlug(title), title, (*article)["description"].asString(),
          (*article)["body"].asString(), *uid, now, now);
      id = static_cast<int64_t>(r.insertId());
      break;
    } catch (const orm::DrogonDbException &) {
      if (attempt >= 3) throw;
    }
  }
  writeTags(client, id, tags);
  sendArticle(cb, id, *uid, k201Created);
}

void updateArticle(const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto art = findArticle(slug);
  if (!art) return sendError(cb, k404NotFound, "article", "not found");
  if (art->authorId != *uid) return sendError(cb, k403Forbidden, "article", "forbidden");
  auto article = requireWrapped(req, cb, "article");
  if (!article) return;

  Json::Value errors;
  for (const char *f : {"title", "description", "body"}) {
    if (!article->isMember(f)) continue;
    const auto &v = (*article)[f];
    if (!v.isString() || v.asString().empty()) errors[f].append("can't be blank");
  }
  std::vector<std::string> tags;
  bool hasTags = article->isMember("tagList");
  if (hasTags && !parseTags((*article)["tagList"], tags)) errors["tagList"].append("is invalid");
  if (!errors.empty()) return sendValidation(cb, errors);

  auto client = db::get();
  if (article->isMember("title")) {
    std::string title = (*article)["title"].asString();
    auto current = client->execSqlSync("SELECT title FROM articles WHERE id = ?", art->id);
    if (current[0]["title"].as<std::string>() != title) {
      client->execSqlSync("UPDATE articles SET title = ?, slug = ? WHERE id = ?", title,
                          uniqueSlug(title), art->id);
    }
  }
  if (article->isMember("description"))
    client->execSqlSync("UPDATE articles SET description = ? WHERE id = ?",
                        (*article)["description"].asString(), art->id);
  if (article->isMember("body"))
    client->execSqlSync("UPDATE articles SET body = ? WHERE id = ?",
                        (*article)["body"].asString(), art->id);
  if (hasTags) writeTags(client, art->id, tags);
  client->execSqlSync("UPDATE articles SET updated_at = ? WHERE id = ?", nowIso(), art->id);
  sendArticle(cb, art->id, *uid);
}

void deleteArticle(const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto art = findArticle(slug);
  if (!art) return sendError(cb, k404NotFound, "article", "not found");
  if (art->authorId != *uid) return sendError(cb, k403Forbidden, "article", "forbidden");
  db::get()->execSqlSync("DELETE FROM articles WHERE id = ?", art->id);
  sendNoContent(cb);
}

void favorite(const HttpRequestPtr &req, Callback &&cb, const std::string &slug, bool on) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto art = findArticle(slug);
  if (!art) return sendError(cb, k404NotFound, "article", "not found");
  if (on)
    db::get()->execSqlSync(
        "INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)", *uid, art->id);
  else
    db::get()->execSqlSync("DELETE FROM favorites WHERE user_id = ? AND article_id = ?", *uid,
                           art->id);
  sendArticle(cb, art->id, *uid);
}

const char *kCommentSelect =
    "SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,"
    " EXISTS(SELECT 1 FROM follows fo WHERE fo.follower_id = ? AND"
    " fo.followee_id = c.author_id) AS following"
    " FROM comments c JOIN users u ON u.id = c.author_id";

Json::Value commentJson(const orm::Row &row) {
  Json::Value c;
  c["id"] = row["id"].as<int64_t>();
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

void listComments(const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
  auto viewer = optionalUser(req, cb);
  if (!viewer) return;
  auto art = findArticle(slug);
  if (!art) return sendError(cb, k404NotFound, "article", "not found");
  auto rows = db::get()->execSqlSync(
      std::string(kCommentSelect) + " WHERE c.article_id = ? ORDER BY c.id ASC", *viewer,
      art->id);
  Json::Value body;
  body["comments"] = Json::Value(Json::arrayValue);
  for (const auto &row : rows) body["comments"].append(commentJson(row));
  sendJson(cb, body);
}

void addComment(const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto art = findArticle(slug);
  if (!art) return sendError(cb, k404NotFound, "article", "not found");
  auto comment = requireWrapped(req, cb, "comment");
  if (!comment) return;
  const auto &b = (*comment)["body"];
  if (!b.isString() || b.asString().empty())
    return sendError(cb, k422UnprocessableEntity, "body", "can't be blank");
  auto client = db::get();
  std::string now = nowIso();
  auto r = client->execSqlSync(
      "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES (?, "
      "?, ?, ?, ?)",
      art->id, *uid, b.asString(), now, now);
  auto rows = client->execSqlSync(std::string(kCommentSelect) + " WHERE c.id = ?", *uid,
                                  static_cast<int64_t>(r.insertId()));
  Json::Value body;
  body["comment"] = commentJson(rows[0]);
  sendJson(cb, body, k201Created);
}

void deleteComment(const HttpRequestPtr &req, Callback &&cb, const std::string &slug,
                   const std::string &idStr) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto art = findArticle(slug);
  if (!art) return sendError(cb, k404NotFound, "article", "not found");
  int64_t id;
  try {
    id = std::stoll(idStr);
  } catch (...) {
    return sendError(cb, k404NotFound, "comment", "not found");
  }
  auto client = db::get();
  auto rows = client->execSqlSync(
      "SELECT author_id FROM comments WHERE id = ? AND article_id = ?", id, art->id);
  if (rows.empty()) return sendError(cb, k404NotFound, "comment", "not found");
  if (rows[0]["author_id"].as<int64_t>() != *uid)
    return sendError(cb, k403Forbidden, "comment", "forbidden");
  client->execSqlSync("DELETE FROM comments WHERE id = ?", id);
  sendNoContent(cb);
}

void listTags(const HttpRequestPtr &, Callback &&cb) {
  auto rows = db::get()->execSqlSync(
      "SELECT tag FROM article_tags GROUP BY tag ORDER BY COUNT(*) DESC, tag ASC");
  Json::Value body;
  body["tags"] = Json::Value(Json::arrayValue);
  for (const auto &row : rows) body["tags"].append(row["tag"].as<std::string>());
  sendJson(cb, body);
}

}  // namespace

void registerArticleRoutes() {
  app().registerHandler("/api/articles", &listArticles, {Get});
  app().registerHandler("/api/articles", &createArticle, {Post});
  app().registerHandler(
      "/api/articles/{slug}",
      [](const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
        switch (req->method()) {
          case Get:
            return getArticle(req, std::move(cb), slug);
          case Put:
            return updateArticle(req, std::move(cb), slug);
          default:
            return deleteArticle(req, std::move(cb), slug);
        }
      },
      {Get, Put, Delete});
  app().registerHandler(
      "/api/articles/{slug}/favorite",
      [](const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
        favorite(req, std::move(cb), slug, req->method() == Post);
      },
      {Post, Delete});
  app().registerHandler(
      "/api/articles/{slug}/comments",
      [](const HttpRequestPtr &req, Callback &&cb, const std::string &slug) {
        if (req->method() == Post) return addComment(req, std::move(cb), slug);
        listComments(req, std::move(cb), slug);
      },
      {Get, Post});
  app().registerHandler(
      "/api/articles/{slug}/comments/{id}",
      [](const HttpRequestPtr &req, Callback &&cb, const std::string &slug,
         const std::string &id) { deleteComment(req, std::move(cb), slug, id); },
      {Delete});
  app().registerHandler("/api/tags", &listTags, {Get});
}
