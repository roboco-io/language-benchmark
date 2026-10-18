#include <drogon/drogon.h>

#include "auth.h"
#include "db.h"
#include "handlers.h"

using namespace drogon;

namespace {

constexpr size_t kMinPasswordLength = 8;

Json::Value userJson(int64_t id) {
  auto rows = db::get()->execSqlSync(
      "SELECT username, email, bio, image FROM users WHERE id = ?", id);
  Json::Value user;
  const auto &row = rows[0];
  user["username"] = row["username"].as<std::string>();
  user["email"] = row["email"].as<std::string>();
  user["bio"] = nullableString(row["bio"]);
  user["image"] = nullableString(row["image"]);
  user["token"] = auth::makeToken(id);
  Json::Value body;
  body["user"] = user;
  return body;
}

// Non-empty string member, or nullopt when missing, null, non-string or blank.
std::optional<std::string> nonBlank(const Json::Value &obj, const char *name) {
  const Json::Value &v = obj[name];
  if (!v.isString() || v.asString().empty()) return std::nullopt;
  return v.asString();
}

bool taken(const char *column, const std::string &value, int64_t exceptId) {
  std::string sql = std::string("SELECT 1 FROM users WHERE ") + column + " = ? AND id != ?";
  return !db::get()->execSqlSync(sql, value, exceptId).empty();
}

void registerUser(const HttpRequestPtr &req, Callback &&cb) {
  auto user = requireWrapped(req, cb, "user");
  if (!user) return;
  auto username = nonBlank(*user, "username");
  auto email = nonBlank(*user, "email");
  auto password = nonBlank(*user, "password");
  Json::Value errors;
  if (!username) errors["username"].append("can't be blank");
  if (!email) errors["email"].append("can't be blank");
  if (!password) errors["password"].append("can't be blank");
  if (!errors.empty()) {
    Json::Value body;
    body["errors"] = errors;
    return sendJson(cb, body, k422UnprocessableEntity);
  }
  if (taken("username", *username, 0)) errors["username"].append("has already been taken");
  if (taken("email", *email, 0)) errors["email"].append("has already been taken");
  if (!errors.empty()) {
    Json::Value body;
    body["errors"] = errors;
    return sendJson(cb, body, k409Conflict);
  }
  int64_t id;
  try {
    auto r = db::get()->execSqlSync(
        "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)", *username,
        *email, auth::hashPassword(*password));
    id = static_cast<int64_t>(r.insertId());
  } catch (const orm::DrogonDbException &) {
    return sendError(cb, k409Conflict, "username", "has already been taken");
  }
  sendJson(cb, userJson(id), k201Created);
}

void login(const HttpRequestPtr &req, Callback &&cb) {
  auto user = requireWrapped(req, cb, "user");
  if (!user) return;
  auto email = nonBlank(*user, "email");
  auto password = nonBlank(*user, "password");
  Json::Value errors;
  if (!email) errors["email"].append("can't be blank");
  if (!password) errors["password"].append("can't be blank");
  if (!errors.empty()) {
    Json::Value body;
    body["errors"] = errors;
    return sendJson(cb, body, k422UnprocessableEntity);
  }
  auto rows =
      db::get()->execSqlSync("SELECT id, password_hash FROM users WHERE email = ?", *email);
  if (rows.empty() ||
      !auth::checkPassword(*password, rows[0]["password_hash"].as<std::string>())) {
    return sendError(cb, k401Unauthorized, "credentials", "invalid");
  }
  sendJson(cb, userJson(rows[0]["id"].as<int64_t>()));
}

void currentUser(const HttpRequestPtr &req, Callback &&cb) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  sendJson(cb, userJson(*uid));
}

void updateUser(const HttpRequestPtr &req, Callback &&cb) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto user = requireWrapped(req, cb, "user");
  if (!user) return;

  Json::Value errors;
  std::optional<std::string> username, email, passwordHash;
  // For bio/image: outer optional = field present, inner = value (nullopt -> NULL).
  std::optional<std::optional<std::string>> bio, image;

  if (user->isMember("username")) {
    username = nonBlank(*user, "username");
    if (!username) errors["username"].append("can't be blank");
  }
  if (user->isMember("email")) {
    email = nonBlank(*user, "email");
    if (!email) errors["email"].append("can't be blank");
  }
  if (user->isMember("password")) {
    auto password = nonBlank(*user, "password");
    if (!password)
      errors["password"].append("can't be blank");
    else if (password->size() < kMinPasswordLength)
      errors["password"].append("is too short (minimum is 8 characters)");
    else
      passwordHash = auth::hashPassword(*password);
  }
  for (auto [name, target] : {std::pair{"bio", &bio}, std::pair{"image", &image}}) {
    if (!user->isMember(name)) continue;
    const Json::Value &v = (*user)[name];
    if (v.isNull() || (v.isString() && v.asString().empty()))
      *target = std::optional<std::string>{};
    else if (v.isString())
      *target = v.asString();
    else
      errors[name].append("is invalid");
  }
  if (!errors.empty()) {
    Json::Value body;
    body["errors"] = errors;
    return sendJson(cb, body, k422UnprocessableEntity);
  }
  if (username && taken("username", *username, *uid))
    errors["username"].append("has already been taken");
  if (email && taken("email", *email, *uid)) errors["email"].append("has already been taken");
  if (!errors.empty()) {
    Json::Value body;
    body["errors"] = errors;
    return sendJson(cb, body, k409Conflict);
  }

  auto client = db::get();
  try {
    if (username)
      client->execSqlSync("UPDATE users SET username = ? WHERE id = ?", *username, *uid);
    if (email) client->execSqlSync("UPDATE users SET email = ? WHERE id = ?", *email, *uid);
    if (passwordHash)
      client->execSqlSync("UPDATE users SET password_hash = ? WHERE id = ?", *passwordHash,
                          *uid);
    if (bio) {
      if (*bio)
        client->execSqlSync("UPDATE users SET bio = ? WHERE id = ?", **bio, *uid);
      else
        client->execSqlSync("UPDATE users SET bio = NULL WHERE id = ?", *uid);
    }
    if (image) {
      if (*image)
        client->execSqlSync("UPDATE users SET image = ? WHERE id = ?", **image, *uid);
      else
        client->execSqlSync("UPDATE users SET image = NULL WHERE id = ?", *uid);
    }
  } catch (const orm::DrogonDbException &) {
    return sendError(cb, k409Conflict, "username", "has already been taken");
  }
  sendJson(cb, userJson(*uid));
}

std::optional<int64_t> findUserId(const std::string &username) {
  auto rows = db::get()->execSqlSync("SELECT id FROM users WHERE username = ?", username);
  if (rows.empty()) return std::nullopt;
  return rows[0]["id"].as<int64_t>();
}

void sendProfile(const Callback &cb, int64_t userId, int64_t viewerId) {
  Json::Value body;
  body["profile"] = *loadProfile(userId, viewerId);
  sendJson(cb, body);
}

void getProfile(const HttpRequestPtr &req, Callback &&cb, const std::string &username) {
  auto viewer = optionalUser(req, cb);
  if (!viewer) return;
  auto target = findUserId(username);
  if (!target) return sendError(cb, k404NotFound, "profile", "not found");
  sendProfile(cb, *target, *viewer);
}

void follow(const HttpRequestPtr &req, Callback &&cb, const std::string &username, bool on) {
  auto uid = requireUser(req, cb);
  if (!uid) return;
  auto target = findUserId(username);
  if (!target) return sendError(cb, k404NotFound, "profile", "not found");
  if (on) {
    if (*target != *uid)
      db::get()->execSqlSync(
          "INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)", *uid,
          *target);
  } else {
    db::get()->execSqlSync("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?",
                           *uid, *target);
  }
  sendProfile(cb, *target, *uid);
}

}  // namespace

std::optional<Json::Value> requireWrapped(const HttpRequestPtr &req, const Callback &cb,
                                          const char *key) {
  auto json = req->getJsonObject();
  if (!json || !json->isObject() || !(*json)[key].isObject()) {
    sendError(cb, k422UnprocessableEntity, "body", "is invalid");
    return std::nullopt;
  }
  return (*json)[key];
}

std::optional<Json::Value> loadProfile(int64_t userId, int64_t viewerId) {
  auto rows = db::get()->execSqlSync(
      "SELECT username, bio, image, EXISTS(SELECT 1 FROM follows WHERE follower_id = ? AND "
      "followee_id = users.id) AS following FROM users WHERE id = ?",
      viewerId, userId);
  if (rows.empty()) return std::nullopt;
  const auto &row = rows[0];
  Json::Value p;
  p["username"] = row["username"].as<std::string>();
  p["bio"] = nullableString(row["bio"]);
  p["image"] = nullableString(row["image"]);
  p["following"] = row["following"].as<int64_t>() != 0;
  return p;
}

void registerUserRoutes() {
  app().registerHandler("/api/users", &registerUser, {Post});
  app().registerHandler("/api/users/login", &login, {Post});
  app().registerHandler("/api/user", &currentUser, {Get});
  app().registerHandler("/api/user", &updateUser, {Put});
  app().registerHandler(
      "/api/profiles/{username}",
      [](const HttpRequestPtr &req, Callback &&cb, const std::string &username) {
        getProfile(req, std::move(cb), username);
      },
      {Get});
  app().registerHandler(
      "/api/profiles/{username}/follow",
      [](const HttpRequestPtr &req, Callback &&cb, const std::string &username) {
        follow(req, std::move(cb), username, req->method() == Post);
      },
      {Post, Delete});
}
