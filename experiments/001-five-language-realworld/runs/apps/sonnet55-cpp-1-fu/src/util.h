// Helpers: JWT (HS256), password hashing, time, slugs and JSON error responses.
#pragma once

#include <drogon/drogon.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <optional>
#include <string>

namespace rw
{

inline std::string envOr(const char *name, const std::string &fallback)
{
    const char *v = std::getenv(name);
    return (v && *v) ? std::string(v) : fallback;
}

// ---------- time ----------

inline std::string nowIso()
{
    using namespace std::chrono;
    auto now = system_clock::now();
    auto ms = duration_cast<milliseconds>(now.time_since_epoch()).count();
    std::time_t secs = static_cast<std::time_t>(ms / 1000);
    std::tm tm{};
    gmtime_r(&secs, &tm);
    char buf[32];
    std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S", &tm);
    char out[48];
    std::snprintf(out, sizeof(out), "%s.%03dZ", buf, static_cast<int>(ms % 1000));
    return out;
}

// ---------- hex / random ----------

inline std::string toHex(const unsigned char *d, size_t n)
{
    static const char *digits = "0123456789abcdef";
    std::string s;
    s.reserve(n * 2);
    for (size_t i = 0; i < n; ++i)
    {
        s.push_back(digits[d[i] >> 4]);
        s.push_back(digits[d[i] & 15]);
    }
    return s;
}

inline std::string randomHex(size_t bytes)
{
    std::string buf(bytes, '\0');
    RAND_bytes(reinterpret_cast<unsigned char *>(buf.data()), static_cast<int>(bytes));
    return toHex(reinterpret_cast<const unsigned char *>(buf.data()), bytes);
}

// ---------- password hashing (PBKDF2-HMAC-SHA256) ----------

constexpr int kPbkdf2Iterations = 10000;

inline std::string pbkdf2(const std::string &password, const std::string &salt)
{
    unsigned char out[32];
    PKCS5_PBKDF2_HMAC(password.data(), static_cast<int>(password.size()),
                      reinterpret_cast<const unsigned char *>(salt.data()),
                      static_cast<int>(salt.size()), kPbkdf2Iterations, EVP_sha256(), 32, out);
    return toHex(out, 32);
}

// Stored format: "<salt>$<hash>"
inline std::string hashPassword(const std::string &password)
{
    std::string salt = randomHex(16);
    return salt + "$" + pbkdf2(password, salt);
}

inline bool constantTimeEq(const std::string &a, const std::string &b)
{
    if (a.size() != b.size()) return false;
    unsigned char r = 0;
    for (size_t i = 0; i < a.size(); ++i) r |= static_cast<unsigned char>(a[i] ^ b[i]);
    return r == 0;
}

inline bool verifyPassword(const std::string &password, const std::string &stored)
{
    auto pos = stored.find('$');
    if (pos == std::string::npos) return false;
    return constantTimeEq(pbkdf2(password, stored.substr(0, pos)), stored.substr(pos + 1));
}

// ---------- JWT (HS256) ----------

inline std::string b64url(const std::string &raw)
{
    std::string s = drogon::utils::base64Encode(reinterpret_cast<const unsigned char *>(raw.data()),
                                                raw.size(), true, false);
    return s;
}

inline std::string hmacSha256(const std::string &key, const std::string &data)
{
    unsigned char out[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    HMAC(EVP_sha256(), key.data(), static_cast<int>(key.size()),
         reinterpret_cast<const unsigned char *>(data.data()), data.size(), out, &len);
    return std::string(reinterpret_cast<char *>(out), len);
}

inline std::string jwtSign(int64_t userId, const std::string &secret)
{
    auto exp = std::chrono::duration_cast<std::chrono::seconds>(
                   std::chrono::system_clock::now().time_since_epoch())
                   .count() +
               60 * 60 * 24 * 7;
    std::string header = b64url(R"({"alg":"HS256","typ":"JWT"})");
    std::string payload = b64url("{\"sub\":" + std::to_string(userId) + ",\"exp\":" + std::to_string(exp) + "}");
    std::string input = header + "." + payload;
    return input + "." + b64url(hmacSha256(secret, input));
}

// Returns the user id when the token is valid and not expired.
inline std::optional<int64_t> jwtVerify(const std::string &token, const std::string &secret)
{
    auto p1 = token.find('.');
    if (p1 == std::string::npos) return std::nullopt;
    auto p2 = token.find('.', p1 + 1);
    if (p2 == std::string::npos) return std::nullopt;
    std::string input = token.substr(0, p2);
    if (!constantTimeEq(b64url(hmacSha256(secret, input)), token.substr(p2 + 1))) return std::nullopt;
    std::string payload = drogon::utils::base64Decode(token.substr(p1 + 1, p2 - p1 - 1));
    Json::Value v;
    Json::CharReaderBuilder rb;
    std::string errs;
    std::istringstream is(payload);
    if (!Json::parseFromStream(rb, is, &v, &errs) || !v.isObject()) return std::nullopt;
    if (!v["sub"].isInt64() || !v["exp"].isInt64()) return std::nullopt;
    auto now = std::chrono::duration_cast<std::chrono::seconds>(
                   std::chrono::system_clock::now().time_since_epoch())
                   .count();
    if (v["exp"].asInt64() < now) return std::nullopt;
    return v["sub"].asInt64();
}

// ---------- slug ----------

inline std::string slugify(const std::string &title)
{
    std::string s;
    bool dash = false;
    for (unsigned char c : title)
    {
        if (std::isalnum(c) && c < 128)
        {
            s.push_back(static_cast<char>(std::tolower(c)));
            dash = false;
        }
        else if (!dash && !s.empty())
        {
            s.push_back('-');
            dash = true;
        }
    }
    while (!s.empty() && s.back() == '-') s.pop_back();
    if (s.empty()) s = "article";
    return s;
}

// ---------- JSON responses ----------

inline drogon::HttpResponsePtr jsonResp(const Json::Value &v, drogon::HttpStatusCode code = drogon::k200OK)
{
    auto r = drogon::HttpResponse::newHttpJsonResponse(v);
    r->setStatusCode(code);
    return r;
}

inline drogon::HttpResponsePtr errorResp(drogon::HttpStatusCode code, const std::string &field,
                                         const std::string &msg)
{
    Json::Value e;
    e["errors"][field].append(msg);
    return jsonResp(e, code);
}

}  // namespace rw
