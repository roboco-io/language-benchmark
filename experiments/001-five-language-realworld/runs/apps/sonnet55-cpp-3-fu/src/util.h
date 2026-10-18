// Small helpers: timestamps, slugs, JSON, password hashing and JWT (HS256).
#pragma once

#include <json/json.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <chrono>
#include <cstdlib>
#include <ctime>
#include <optional>
#include <string>

namespace util
{
inline std::string envOr(const char *name, const std::string &fallback)
{
    const char *v = std::getenv(name);
    return (v && *v) ? std::string(v) : fallback;
}

// ISO 8601 UTC timestamp with millisecond precision.
inline std::string nowIso()
{
    using namespace std::chrono;
    auto tp = system_clock::now();
    auto ms = duration_cast<milliseconds>(tp.time_since_epoch()) % 1000;
    std::time_t t = system_clock::to_time_t(tp);
    std::tm tm{};
    gmtime_r(&t, &tm);
    char buf[32];
    std::strftime(buf, sizeof buf, "%Y-%m-%dT%H:%M:%S", &tm);
    char out[48];
    std::snprintf(out, sizeof out, "%s.%03dZ", buf, static_cast<int>(ms.count()));
    return out;
}

inline std::string hex(const unsigned char *d, size_t n)
{
    static const char *digits = "0123456789abcdef";
    std::string s;
    for (size_t i = 0; i < n; ++i)
    {
        s += digits[d[i] >> 4];
        s += digits[d[i] & 15];
    }
    return s;
}

inline std::string randomHex(size_t bytes)
{
    std::string buf(bytes, '\0');
    RAND_bytes(reinterpret_cast<unsigned char *>(buf.data()), (int)bytes);
    return hex(reinterpret_cast<const unsigned char *>(buf.data()), bytes);
}

inline std::string slugify(const std::string &title)
{
    std::string s;
    bool dash = false;
    for (unsigned char c : title)
    {
        if (std::isalnum(c))
        {
            if (dash && !s.empty()) s += '-';
            s += (char)std::tolower(c);
            dash = false;
        }
        else
            dash = true;
    }
    return s.empty() ? "article" : s;
}

inline bool isBlank(const std::string &s)
{
    for (unsigned char c : s)
        if (!std::isspace(c)) return false;
    return true;
}

inline std::optional<Json::Value> parseJson(const std::string &body)
{
    Json::CharReaderBuilder b;
    Json::Value v;
    std::string errs;
    std::unique_ptr<Json::CharReader> r(b.newCharReader());
    if (!r->parse(body.data(), body.data() + body.size(), &v, &errs))
        return std::nullopt;
    return v;
}

inline std::string toJsonString(const Json::Value &v)
{
    Json::StreamWriterBuilder b;
    b["indentation"] = "";
    return Json::writeString(b, v);
}

// ---- base64url ----
inline std::string b64urlEncode(const std::string &in)
{
    static const char *tbl =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    size_t i = 0;
    while (i + 2 < in.size())
    {
        unsigned v = ((unsigned char)in[i] << 16) | ((unsigned char)in[i + 1] << 8) |
                     (unsigned char)in[i + 2];
        out += tbl[v >> 18];
        out += tbl[(v >> 12) & 63];
        out += tbl[(v >> 6) & 63];
        out += tbl[v & 63];
        i += 3;
    }
    if (i + 1 == in.size())
    {
        unsigned v = (unsigned char)in[i] << 16;
        out += tbl[v >> 18];
        out += tbl[(v >> 12) & 63];
    }
    else if (i + 2 == in.size())
    {
        unsigned v = ((unsigned char)in[i] << 16) | ((unsigned char)in[i + 1] << 8);
        out += tbl[v >> 18];
        out += tbl[(v >> 12) & 63];
        out += tbl[(v >> 6) & 63];
    }
    return out;
}

inline std::optional<std::string> b64urlDecode(const std::string &in)
{
    auto val = [](char c) -> int {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '-') return 62;
        if (c == '_') return 63;
        return -1;
    };
    std::string out;
    unsigned acc = 0;
    int bits = 0;
    for (char c : in)
    {
        int v = val(c);
        if (v < 0) return std::nullopt;
        acc = (acc << 6) | (unsigned)v;
        bits += 6;
        if (bits >= 8)
        {
            bits -= 8;
            out += (char)((acc >> bits) & 0xFF);
        }
    }
    return out;
}

inline std::string hmacSha256(const std::string &key, const std::string &data)
{
    unsigned char md[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    HMAC(EVP_sha256(), key.data(), (int)key.size(),
         reinterpret_cast<const unsigned char *>(data.data()), data.size(), md, &len);
    return std::string(reinterpret_cast<char *>(md), len);
}

// ---- JWT (HS256) ----
inline const std::string &jwtSecret()
{
    static const std::string s = envOr("JWT_SECRET", "realworld-dev-secret-change-me");
    return s;
}

inline std::string jwtCreate(int64_t userId, const std::string &username)
{
    using namespace std::chrono;
    int64_t iat = duration_cast<seconds>(system_clock::now().time_since_epoch()).count();
    Json::Value p;
    p["sub"] = (Json::Int64)userId;
    p["username"] = username;
    p["iat"] = (Json::Int64)iat;
    p["exp"] = (Json::Int64)(iat + 7 * 24 * 3600);
    std::string head = b64urlEncode(R"({"alg":"HS256","typ":"JWT"})");
    std::string signing = head + "." + b64urlEncode(toJsonString(p));
    return signing + "." + b64urlEncode(hmacSha256(jwtSecret(), signing));
}

// Returns the user id when the token is valid and not expired.
inline std::optional<int64_t> jwtVerify(const std::string &token)
{
    auto d1 = token.find('.');
    if (d1 == std::string::npos) return std::nullopt;
    auto d2 = token.find('.', d1 + 1);
    if (d2 == std::string::npos) return std::nullopt;
    std::string signing = token.substr(0, d2);
    auto sig = b64urlDecode(token.substr(d2 + 1));
    if (!sig) return std::nullopt;
    std::string expect = hmacSha256(jwtSecret(), signing);
    if (sig->size() != expect.size() ||
        CRYPTO_memcmp(sig->data(), expect.data(), expect.size()) != 0)
        return std::nullopt;
    auto header = b64urlDecode(token.substr(0, d1));
    if (!header || *header != R"({"alg":"HS256","typ":"JWT"})") return std::nullopt;
    auto payload = b64urlDecode(token.substr(d1 + 1, d2 - d1 - 1));
    if (!payload) return std::nullopt;
    auto json = parseJson(*payload);
    if (!json || !json->isObject() || !(*json)["sub"].isInt64() || !(*json)["exp"].isInt64())
        return std::nullopt;
    using namespace std::chrono;
    int64_t now = duration_cast<seconds>(system_clock::now().time_since_epoch()).count();
    if ((*json)["exp"].asInt64() < now) return std::nullopt;
    return (*json)["sub"].asInt64();
}

// ---- Password hashing (PBKDF2-HMAC-SHA256) ----
constexpr int kPbkdf2Iterations = 50000;

inline std::string hashPassword(const std::string &password)
{
    unsigned char salt[16];
    RAND_bytes(salt, sizeof salt);
    unsigned char out[32];
    PKCS5_PBKDF2_HMAC(password.data(), (int)password.size(), salt, sizeof salt,
                      kPbkdf2Iterations, EVP_sha256(), sizeof out, out);
    return "pbkdf2$" + std::to_string(kPbkdf2Iterations) + "$" + hex(salt, sizeof salt) +
           "$" + hex(out, sizeof out);
}

inline bool verifyPassword(const std::string &password, const std::string &stored)
{
    auto p1 = stored.find('$');
    auto p2 = stored.find('$', p1 + 1);
    auto p3 = stored.find('$', p2 + 1);
    if (p1 == std::string::npos || p2 == std::string::npos || p3 == std::string::npos)
        return false;
    int iters = std::atoi(stored.substr(p1 + 1, p2 - p1 - 1).c_str());
    std::string saltHex = stored.substr(p2 + 1, p3 - p2 - 1);
    std::string want = stored.substr(p3 + 1);
    unsigned char salt[64];
    size_t sl = saltHex.size() / 2;
    if (sl > sizeof salt || iters <= 0) return false;
    for (size_t i = 0; i < sl; ++i)
        salt[i] = (unsigned char)std::stoi(saltHex.substr(i * 2, 2), nullptr, 16);
    unsigned char out[32];
    PKCS5_PBKDF2_HMAC(password.data(), (int)password.size(), salt, (int)sl, iters,
                      EVP_sha256(), sizeof out, out);
    std::string got = hex(out, sizeof out);
    return got.size() == want.size() &&
           CRYPTO_memcmp(got.data(), want.data(), got.size()) == 0;
}
}  // namespace util
