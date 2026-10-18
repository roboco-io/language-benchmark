#include "auth.h"

#include "util.h"

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>

#include <chrono>
#include <cstdlib>
#include <vector>

namespace rw
{
namespace
{
std::string gSecret;
constexpr int64_t kTokenLifetimeSeconds = 60 * 60 * 24 * 30;  // 30 days
constexpr int kPbkdf2Iterations = 50000;

std::string b64url(const std::string &data)
{
    return drogon::utils::base64Encode(
        reinterpret_cast<const unsigned char *>(data.data()),
        data.size(),
        true,
        false);
}

std::string b64urlDecode(std::string data)
{
    for (auto &c : data)
    {
        if (c == '-')
            c = '+';
        else if (c == '_')
            c = '/';
    }
    while (data.size() % 4 != 0)
        data.push_back('=');
    return drogon::utils::base64Decode(data);
}

std::string hmacSha256(const std::string &key, const std::string &data)
{
    unsigned char out[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    HMAC(EVP_sha256(),
         key.data(),
         static_cast<int>(key.size()),
         reinterpret_cast<const unsigned char *>(data.data()),
         data.size(),
         out,
         &len);
    return std::string(reinterpret_cast<char *>(out), len);
}

bool constantTimeEquals(const std::string &a, const std::string &b)
{
    return a.size() == b.size() &&
           CRYPTO_memcmp(a.data(), b.data(), a.size()) == 0;
}

std::string toHex(const unsigned char *data, size_t len)
{
    static const char *digits = "0123456789abcdef";
    std::string out;
    out.reserve(len * 2);
    for (size_t i = 0; i < len; ++i)
    {
        out.push_back(digits[data[i] >> 4]);
        out.push_back(digits[data[i] & 0x0f]);
    }
    return out;
}

std::string pbkdf2(const std::string &password,
                   const std::string &salt,
                   int iterations)
{
    unsigned char out[32];
    PKCS5_PBKDF2_HMAC(password.data(),
                      static_cast<int>(password.size()),
                      reinterpret_cast<const unsigned char *>(salt.data()),
                      static_cast<int>(salt.size()),
                      iterations,
                      EVP_sha256(),
                      sizeof(out),
                      out);
    return toHex(out, sizeof(out));
}

int64_t nowSeconds()
{
    using namespace std::chrono;
    return duration_cast<seconds>(system_clock::now().time_since_epoch())
        .count();
}

// Verifies the token signature and expiry. Returns the user id on success.
std::optional<int64_t> verifyToken(const std::string &token)
{
    auto p1 = token.find('.');
    if (p1 == std::string::npos)
        return std::nullopt;
    auto p2 = token.find('.', p1 + 1);
    if (p2 == std::string::npos)
        return std::nullopt;
    std::string signingInput = token.substr(0, p2);
    std::string signature = token.substr(p2 + 1);
    if (!constantTimeEquals(b64url(hmacSha256(gSecret, signingInput)),
                            signature))
        return std::nullopt;

    std::string header = b64urlDecode(token.substr(0, p1));
    std::string payload =
        b64urlDecode(token.substr(p1 + 1, p2 - p1 - 1));
    Json::Value hdr, claims;
    Json::CharReaderBuilder builder;
    std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
    std::string errs;
    if (!reader->parse(header.data(), header.data() + header.size(), &hdr, &errs) ||
        !reader->parse(payload.data(), payload.data() + payload.size(), &claims, &errs))
        return std::nullopt;
    if (!hdr.isObject() || hdr["alg"].asString() != "HS256")
        return std::nullopt;
    if (!claims.isObject() || !claims["sub"].isIntegral() ||
        !claims["exp"].isIntegral())
        return std::nullopt;
    if (claims["exp"].asInt64() < nowSeconds())
        return std::nullopt;
    return claims["sub"].asInt64();
}

drogon::Task<AuthResult> authenticate(const drogon::HttpRequestPtr &req,
                                      bool required)
{
    AuthResult result;
    const std::string &header = req->getHeader("authorization");
    if (header.empty())
    {
        if (required)
            result.error = errorResponse(drogon::k401Unauthorized,
                                         "token",
                                         "is missing");
        co_return result;
    }
    std::string token;
    if (header.rfind("Token ", 0) == 0)
        token = header.substr(6);
    else if (header.rfind("Bearer ", 0) == 0)
        token = header.substr(7);
    auto id = token.empty() ? std::nullopt : verifyToken(token);
    if (id)
        result.user = co_await loadUserById(*id);
    if (!result.user)
        result.error =
            errorResponse(drogon::k401Unauthorized, "token", "is invalid");
    co_return result;
}
}  // namespace

void initJwtSecret()
{
    const char *env = std::getenv("JWT_SECRET");
    if (env && *env)
    {
        gSecret = env;
    }
    else
    {
        // Without a configured secret, a random one is generated per process.
        // Tokens then stay valid only until the server restarts.
        gSecret = randomHex(32);
        LOG_WARN << "JWT_SECRET is not set; using a random per-process secret";
    }
}

std::string createToken(const User &user)
{
    Json::StreamWriterBuilder wb;
    wb["indentation"] = "";
    Json::Value header;
    header["alg"] = "HS256";
    header["typ"] = "JWT";
    Json::Value claims;
    auto now = nowSeconds();
    claims["sub"] = Json::Int64(user.id);
    claims["username"] = user.username;
    claims["iat"] = Json::Int64(now);
    claims["exp"] = Json::Int64(now + kTokenLifetimeSeconds);
    std::string input = b64url(Json::writeString(wb, header)) + "." +
                        b64url(Json::writeString(wb, claims));
    return input + "." + b64url(hmacSha256(gSecret, input));
}

std::string hashPassword(const std::string &password)
{
    std::string salt = randomHex(16);
    return "pbkdf2_sha256$" + std::to_string(kPbkdf2Iterations) + "$" + salt +
           "$" + pbkdf2(password, salt, kPbkdf2Iterations);
}

bool verifyPassword(const std::string &password, const std::string &stored)
{
    std::vector<std::string> parts;
    size_t start = 0;
    while (true)
    {
        auto pos = stored.find('$', start);
        parts.push_back(stored.substr(start, pos - start));
        if (pos == std::string::npos)
            break;
        start = pos + 1;
    }
    if (parts.size() != 4 || parts[0] != "pbkdf2_sha256")
        return false;
    int iterations = std::atoi(parts[1].c_str());
    if (iterations <= 0)
        return false;
    return constantTimeEquals(pbkdf2(password, parts[2], iterations),
                              parts[3]);
}

drogon::Task<AuthResult> requireAuth(const drogon::HttpRequestPtr &req)
{
    co_return co_await authenticate(req, true);
}

drogon::Task<AuthResult> optionalAuth(const drogon::HttpRequestPtr &req)
{
    co_return co_await authenticate(req, false);
}

drogon::Task<std::optional<User>> loadUserById(int64_t id)
{
    auto r = co_await db()->execSqlCoro(
        "SELECT id, username, email, bio, image FROM users WHERE id = ?", id);
    if (r.empty())
        co_return std::nullopt;
    User u;
    u.id = r[0]["id"].as<int64_t>();
    u.username = r[0]["username"].as<std::string>();
    u.email = r[0]["email"].as<std::string>();
    if (!r[0]["bio"].isNull())
        u.bio = r[0]["bio"].as<std::string>();
    if (!r[0]["image"].isNull())
        u.image = r[0]["image"].as<std::string>();
    co_return u;
}
}  // namespace rw
