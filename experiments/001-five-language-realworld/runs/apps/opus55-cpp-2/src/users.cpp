#include "auth.h"
#include "routes.h"
#include "util.h"

using namespace drogon;

namespace rw
{
namespace
{
constexpr size_t kMinPasswordLength = 8;

Json::Value userJson(const User &u)
{
    Json::Value v;
    v["email"] = u.email;
    v["token"] = createToken(u);
    v["username"] = u.username;
    v["bio"] = u.bio ? Json::Value(*u.bio) : Json::Value(Json::nullValue);
    v["image"] = u.image ? Json::Value(*u.image) : Json::Value(Json::nullValue);
    Json::Value root;
    root["user"] = v;
    return root;
}

Json::Value profileJson(const User &u, bool following)
{
    Json::Value v;
    v["username"] = u.username;
    v["bio"] = u.bio ? Json::Value(*u.bio) : Json::Value(Json::nullValue);
    v["image"] = u.image ? Json::Value(*u.image) : Json::Value(Json::nullValue);
    v["following"] = following;
    Json::Value root;
    root["profile"] = v;
    return root;
}

// Extracts body[wrapper] as an object, or returns nullptr when absent.
const Json::Value *wrapped(const std::optional<Json::Value> &body,
                           const char *wrapper)
{
    if (!body || !body->isObject())
        return nullptr;
    const Json::Value &inner = (*body)[wrapper];
    if (!inner.isObject())
        return nullptr;
    return &inner;
}

// Returns the string field, or an empty string when missing or not a string.
std::string stringField(const Json::Value &obj, const char *name)
{
    const Json::Value &v = obj[name];
    return v.isString() ? v.asString() : std::string();
}

Task<std::optional<User>> loadUserByName(const std::string &username)
{
    auto r = co_await db()->execSqlCoro(
        "SELECT id FROM users WHERE username = ?", username);
    if (r.empty())
        co_return std::nullopt;
    co_return co_await loadUserById(r[0]["id"].as<int64_t>());
}

Task<bool> isFollowing(int64_t follower, int64_t followee)
{
    auto r = co_await db()->execSqlCoro(
        "SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = ?",
        follower,
        followee);
    co_return !r.empty();
}

// Returns a 409 response when the username or email is taken by another user.
Task<HttpResponsePtr> checkUnique(const std::string &username,
                                  const std::string &email,
                                  int64_t selfId)
{
    auto r = co_await db()->execSqlCoro(
        "SELECT 1 FROM users WHERE username = ? AND id != ?", username, selfId);
    if (!r.empty())
        co_return errorResponse(k409Conflict, "username", "has already been taken");
    r = co_await db()->execSqlCoro(
        "SELECT 1 FROM users WHERE email = ? AND id != ?", email, selfId);
    if (!r.empty())
        co_return errorResponse(k409Conflict, "email", "has already been taken");
    co_return nullptr;
}

Task<HttpResponsePtr> registerUser(HttpRequestPtr req)
{
    auto body = parseBody(req);
    const Json::Value *in = wrapped(body, "user");
    if (!in)
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    std::string username = stringField(*in, "username");
    std::string email = stringField(*in, "email");
    std::string password = stringField(*in, "password");
    if (username.empty())
        co_return errorResponse(k422UnprocessableEntity, "username", "can't be blank");
    if (email.empty())
        co_return errorResponse(k422UnprocessableEntity, "email", "can't be blank");
    if (password.empty())
        co_return errorResponse(k422UnprocessableEntity, "password", "can't be blank");
    if (password.size() < kMinPasswordLength)
        co_return errorResponse(k422UnprocessableEntity,
                                "password",
                                "is too short (minimum is 8 characters)");

    if (auto conflict = co_await checkUnique(username, email, 0))
        co_return conflict;

    int64_t id = 0;
    std::exception_ptr insertError;
    try
    {
        auto r = co_await db()->execSqlCoro(
            "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
            username,
            email,
            hashPassword(password));
        id = static_cast<int64_t>(r.insertId());
    }
    catch (const orm::DrogonDbException &)
    {
        insertError = std::current_exception();
    }
    if (insertError)
    {
        // A concurrent request may have created the same username or email.
        if (auto conflict = co_await checkUnique(username, email, 0))
            co_return conflict;
        std::rethrow_exception(insertError);
    }
    auto user = co_await loadUserById(id);
    co_return jsonResponse(userJson(*user), k201Created);
}

Task<HttpResponsePtr> login(HttpRequestPtr req)
{
    auto body = parseBody(req);
    const Json::Value *in = wrapped(body, "user");
    if (!in)
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    std::string email = stringField(*in, "email");
    std::string password = stringField(*in, "password");
    if (email.empty())
        co_return errorResponse(k422UnprocessableEntity, "email", "can't be blank");
    if (password.empty())
        co_return errorResponse(k422UnprocessableEntity, "password", "can't be blank");

    auto r = co_await db()->execSqlCoro(
        "SELECT id, password_hash FROM users WHERE email = ?", email);
    if (r.empty() ||
        !verifyPassword(password, r[0]["password_hash"].as<std::string>()))
        co_return errorResponse(k401Unauthorized, "credentials", "invalid");
    auto user = co_await loadUserById(r[0]["id"].as<int64_t>());
    co_return jsonResponse(userJson(*user));
}

Task<HttpResponsePtr> currentUser(HttpRequestPtr req)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    co_return jsonResponse(userJson(*auth.user));
}

Task<HttpResponsePtr> updateUser(HttpRequestPtr req)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto body = parseBody(req);
    const Json::Value *in = wrapped(body, "user");
    if (!in)
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");

    User u = *auth.user;
    std::optional<std::string> newPasswordHash;

    // Required fields: when present they must be non-empty strings.
    for (const char *name : {"username", "email"})
    {
        if (!in->isMember(name))
            continue;
        const Json::Value &v = (*in)[name];
        if (!v.isString() || v.asString().empty())
            co_return errorResponse(k422UnprocessableEntity, name, "can't be blank");
        (std::string_view(name) == "username" ? u.username : u.email) =
            v.asString();
    }
    if (in->isMember("password"))
    {
        const Json::Value &v = (*in)["password"];
        if (!v.isString() || v.asString().empty())
            co_return errorResponse(k422UnprocessableEntity, "password", "can't be blank");
        if (v.asString().size() < kMinPasswordLength)
            co_return errorResponse(k422UnprocessableEntity,
                                    "password",
                                    "is too short (minimum is 8 characters)");
        newPasswordHash = hashPassword(v.asString());
    }
    // Nullable fields: null or an empty string clears the value.
    for (const char *name : {"bio", "image"})
    {
        if (!in->isMember(name))
            continue;
        const Json::Value &v = (*in)[name];
        auto &target = std::string_view(name) == "bio" ? u.bio : u.image;
        if (v.isNull())
            target.reset();
        else if (v.isString())
            target = v.asString().empty() ? std::nullopt
                                          : std::optional<std::string>(v.asString());
        else
            co_return errorResponse(k422UnprocessableEntity, name, "is invalid");
    }

    if (auto conflict = co_await checkUnique(u.username, u.email, u.id))
        co_return conflict;

    // NULLIF maps the empty string back to SQL NULL for cleared fields.
    co_await db()->execSqlCoro(
        "UPDATE users SET username = ?, email = ?, bio = NULLIF(?, ''), "
        "image = NULLIF(?, '') WHERE id = ?",
        u.username,
        u.email,
        u.bio.value_or(""),
        u.image.value_or(""),
        u.id);
    if (newPasswordHash)
        co_await db()->execSqlCoro(
            "UPDATE users SET password_hash = ? WHERE id = ?",
            *newPasswordHash,
            u.id);
    auto user = co_await loadUserById(u.id);
    co_return jsonResponse(userJson(*user));
}

Task<HttpResponsePtr> getProfile(HttpRequestPtr req, std::string username)
{
    auto auth = co_await optionalAuth(req);
    if (auth.error)
        co_return auth.error;
    auto target = co_await loadUserByName(username);
    if (!target)
        co_return errorResponse(k404NotFound, "profile", "not found");
    bool following = false;
    if (auth.user)
        following = co_await isFollowing(auth.user->id, target->id);
    co_return jsonResponse(profileJson(*target, following));
}

Task<HttpResponsePtr> setFollow(HttpRequestPtr req,
                                std::string username,
                                bool follow)
{
    auto auth = co_await requireAuth(req);
    if (auth.error)
        co_return auth.error;
    auto target = co_await loadUserByName(username);
    if (!target)
        co_return errorResponse(k404NotFound, "profile", "not found");
    if (follow)
        co_await db()->execSqlCoro(
            "INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)",
            auth.user->id,
            target->id);
    else
        co_await db()->execSqlCoro(
            "DELETE FROM follows WHERE follower_id = ? AND followee_id = ?",
            auth.user->id,
            target->id);
    co_return jsonResponse(profileJson(*target, follow));
}
}  // namespace

void registerUserRoutes()
{
    app().registerHandler("/api/users", &registerUser, {Post});
    app().registerHandler("/api/users/login", &login, {Post});
    app().registerHandler("/api/user", &currentUser, {Get});
    app().registerHandler("/api/user", &updateUser, {Put});
    app().registerHandler("/api/profiles/{username}", &getProfile, {Get});
    app().registerHandler(
        "/api/profiles/{username}/follow",
        [](HttpRequestPtr req, std::string username) -> Task<HttpResponsePtr> {
            co_return co_await setFollow(req, std::move(username), true);
        },
        {Post});
    app().registerHandler(
        "/api/profiles/{username}/follow",
        [](HttpRequestPtr req, std::string username) -> Task<HttpResponsePtr> {
            co_return co_await setFollow(req, std::move(username), false);
        },
        {Delete});
}
}  // namespace rw
