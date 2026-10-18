#include "auth.h"

using namespace drogon;

namespace
{
Json::Value userJson(const orm::Row &row, int64_t userId)
{
    Json::Value user;
    user["email"] = row["email"].as<std::string>();
    user["token"] = makeToken(userId);
    user["username"] = row["username"].as<std::string>();
    user["bio"] = nullableText(row["bio"]);
    user["image"] = nullableText(row["image"]);
    Json::Value body;
    body["user"] = user;
    return body;
}

Task<std::optional<orm::Row>> findUser(int64_t id)
{
    auto rows = co_await db()->execSqlCoro(
        "SELECT id, email, username, password_hash, bio, image FROM users WHERE id = ?",
        id);
    if (rows.empty())
        co_return std::nullopt;
    co_return rows[0];
}

// Returns a 409 response if the username or email belongs to another user.
Task<HttpResponsePtr> checkTaken(const std::string &username,
                                 const std::string &email,
                                 int64_t exceptId)
{
    auto byName = co_await db()->execSqlCoro(
        "SELECT 1 FROM users WHERE username = ? AND id != ?", username, exceptId);
    if (!byName.empty())
        co_return errorResponse(k409Conflict, "username", "has already been taken");
    auto byEmail = co_await db()->execSqlCoro(
        "SELECT 1 FROM users WHERE email = ? AND id != ?", email, exceptId);
    if (!byEmail.empty())
        co_return errorResponse(k409Conflict, "email", "has already been taken");
    co_return nullptr;
}

Task<HttpResponsePtr> registerUser(HttpRequestPtr req)
{
    auto body = parseBody(req);
    if (!body || !(*body)["user"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["user"];

    Json::Value errors(Json::objectValue);
    for (const char *field : {"username", "email", "password"})
        if (!isNonEmptyString(in[field]))
            errors[field].append("can't be blank");
    if (!errors.empty())
        co_return validationResponse(errors);

    std::string username = in["username"].asString();
    std::string email = in["email"].asString();
    if (auto taken = co_await checkTaken(username, email, 0))
        co_return taken;

    try
    {
        auto result = co_await db()->execSqlCoro(
            "INSERT INTO users (email, username, password_hash) VALUES (?, ?, ?)",
            email,
            username,
            hashPassword(in["password"].asString()));
        int64_t id = static_cast<int64_t>(result.insertId());
        auto row = co_await findUser(id);
        co_return jsonResponse(userJson(*row, id), k201Created);
    }
    catch (const orm::DrogonDbException &)
    {
        // A concurrent request inserted the same username or email.
        co_return errorResponse(k409Conflict, "username", "has already been taken");
    }
}

Task<HttpResponsePtr> login(HttpRequestPtr req)
{
    auto body = parseBody(req);
    if (!body || !(*body)["user"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["user"];

    Json::Value errors(Json::objectValue);
    for (const char *field : {"email", "password"})
        if (!isNonEmptyString(in[field]))
            errors[field].append("can't be blank");
    if (!errors.empty())
        co_return validationResponse(errors);

    auto rows = co_await db()->execSqlCoro(
        "SELECT id, email, username, password_hash, bio, image FROM users WHERE email = ?",
        in["email"].asString());
    if (rows.empty() ||
        !verifyPassword(in["password"].asString(),
                        rows[0]["password_hash"].as<std::string>()))
        co_return errorResponse(k401Unauthorized, "credentials", "invalid");
    co_return jsonResponse(userJson(rows[0], rows[0]["id"].as<int64_t>()));
}

Task<HttpResponsePtr> currentUser(HttpRequestPtr req)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto row = co_await findUser(*auth.userId);
    if (!row)
        co_return errorResponse(k401Unauthorized, "token", "is invalid");
    co_return jsonResponse(userJson(*row, *auth.userId));
}

Task<HttpResponsePtr> updateUser(HttpRequestPtr req)
{
    auto auth = authenticate(req, true);
    if (auth.error)
        co_return auth.error;
    auto body = parseBody(req);
    if (!body || !(*body)["user"].isObject())
        co_return errorResponse(k422UnprocessableEntity, "body", "is invalid");
    const Json::Value &in = (*body)["user"];

    auto row = co_await findUser(*auth.userId);
    if (!row)
        co_return errorResponse(k401Unauthorized, "token", "is invalid");

    std::string email = (*row)["email"].as<std::string>();
    std::string username = (*row)["username"].as<std::string>();
    std::string passwordHash = (*row)["password_hash"].as<std::string>();
    // Empty string stands for NULL; the UPDATE converts it with NULLIF.
    std::string bio = (*row)["bio"].isNull() ? "" : (*row)["bio"].as<std::string>();
    std::string image =
        (*row)["image"].isNull() ? "" : (*row)["image"].as<std::string>();

    Json::Value errors(Json::objectValue);
    if (in.isMember("email"))
    {
        if (isNonEmptyString(in["email"]))
            email = in["email"].asString();
        else
            errors["email"].append("can't be blank");
    }
    if (in.isMember("username"))
    {
        if (isNonEmptyString(in["username"]))
            username = in["username"].asString();
        else
            errors["username"].append("can't be blank");
    }
    if (in.isMember("password"))
    {
        if (!isNonEmptyString(in["password"]))
            errors["password"].append("can't be blank");
        else if (in["password"].asString().size() < 8)
            errors["password"].append("is too short (minimum is 8 characters)");
        else
            passwordHash = hashPassword(in["password"].asString());
    }
    for (auto [field, target] : {std::pair{"bio", &bio}, std::pair{"image", &image}})
    {
        if (!in.isMember(field))
            continue;
        const Json::Value &v = in[field];
        if (v.isNull())
            *target = "";
        else if (v.isString())
            *target = v.asString();
        else
            errors[field].append("is invalid");
    }
    if (!errors.empty())
        co_return validationResponse(errors);

    if (auto taken = co_await checkTaken(username, email, *auth.userId))
        co_return taken;

    try
    {
        co_await db()->execSqlCoro(
            "UPDATE users SET email = ?, username = ?, password_hash = ?, "
            "bio = NULLIF(?, ''), image = NULLIF(?, '') WHERE id = ?",
            email,
            username,
            passwordHash,
            bio,
            image,
            *auth.userId);
    }
    catch (const orm::DrogonDbException &)
    {
        co_return errorResponse(k409Conflict, "username", "has already been taken");
    }
    auto updated = co_await findUser(*auth.userId);
    co_return jsonResponse(userJson(*updated, *auth.userId));
}
}  // namespace

void registerUserRoutes()
{
    app().registerHandler("/api/users", &registerUser, {Post});
    app().registerHandler("/api/users/login", &login, {Post});
    app().registerHandler("/api/user", &currentUser, {Get});
    app().registerHandler("/api/user", &updateUser, {Put});
}
