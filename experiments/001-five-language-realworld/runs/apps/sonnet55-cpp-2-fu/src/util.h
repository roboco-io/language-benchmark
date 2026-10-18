// Helpers: time, slugs, password hashing, JWT (HS256), JSON responses.
#pragma once

#include <drogon/drogon.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <chrono>
#include <cstdint>
#include <ctime>
#include <string>

namespace rw
{
// ---------- time ----------

inline std::string nowIso()
{
    using namespace std::chrono;
    auto tp = system_clock::now();
    auto ms = duration_cast<milliseconds>(tp.time_since_epoch()).count();
    std::time_t secs = static_cast<std::time_t>(ms / 1000);
    std::tm tm{};
    gmtime_r(&secs, &tm);
    char buf[40];
    std::snprintf(buf,
                  sizeof(buf),
                  "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                  tm.tm_year + 1900,
                  tm.tm_mon + 1,
                  tm.tm_mday,
                  tm.tm_hour,
                  tm.tm_min,
                  tm.tm_sec,
                  static_cast<int>(ms % 1000));
    return buf;
}

// ---------- strings ----------

inline std::string trim(const std::string &s)
{
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos)
        return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

inline std::string toHex(const unsigned char *d, size_t n)
{
    static const char *k = "0123456789abcdef";
    std::string out;
    out.reserve(n * 2);
    for (size_t i = 0; i < n; ++i)
    {
        out.push_back(k[d[i] >> 4]);
        out.push_back(k[d[i] & 15]);
    }
    return out;
}

inline std::string randomHex(size_t bytes)
{
    std::string buf(bytes, '\0');
    RAND_bytes(reinterpret_cast<unsigned char *>(buf.data()),
               static_cast<int>(bytes));
    return toHex(reinterpret_cast<const unsigned char *>(buf.data()), bytes);
}

inline std::string slugify(const std::string &title)
{
    std::string out;
    bool dash = false;
    for (unsigned char c : title)
    {
        if (std::isalnum(c) && c < 128)
        {
            if (dash && !out.empty())
                out.push_back('-');
            dash = false;
            out.push_back(static_cast<char>(std::tolower(c)));
        }
        else
        {
            dash = true;
        }
    }
    return out.empty() ? "article" : out;
}

// ---------- password hashing (PBKDF2-HMAC-SHA256) ----------

constexpr int kPbkdf2Iterations = 10000;

inline std::string pbkdf2(const std::string &password,
                          const std::string &salt)
{
    unsigned char out[32];
    PKCS5_PBKDF2_HMAC(password.data(),
                      static_cast<int>(password.size()),
                      reinterpret_cast<const unsigned char *>(salt.data()),
                      static_cast<int>(salt.size()),
                      kPbkdf2Iterations,
                      EVP_sha256(),
                      sizeof(out),
                      out);
    return toHex(out, sizeof(out));
}

inline std::string hashPassword(const std::string &password)
{
    std::string salt = randomHex(16);
    return salt + "$" + pbkdf2(password, salt);
}

inline bool verifyPassword(const std::string &password,
                           const std::string &stored)
{
    auto pos = stored.find('$');
    if (pos == std::string::npos)
        return false;
    std::string expect = pbkdf2(password, stored.substr(0, pos));
    std::string actual = stored.substr(pos + 1);
    return expect.size() == actual.size() &&
           CRYPTO_memcmp(expect.data(), actual.data(), expect.size()) == 0;
}

// ---------- JWT (HS256) ----------

inline std::string b64url(const unsigned char *d, size_t n)
{
    static const char *k =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    size_t i = 0;
    for (; i + 2 < n; i += 3)
    {
        uint32_t v = (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
        out.push_back(k[(v >> 18) & 63]);
        out.push_back(k[(v >> 12) & 63]);
        out.push_back(k[(v >> 6) & 63]);
        out.push_back(k[v & 63]);
    }
    if (i + 1 == n)
    {
        uint32_t v = d[i] << 16;
        out.push_back(k[(v >> 18) & 63]);
        out.push_back(k[(v >> 12) & 63]);
    }
    else if (i + 2 == n)
    {
        uint32_t v = (d[i] << 16) | (d[i + 1] << 8);
        out.push_back(k[(v >> 18) & 63]);
        out.push_back(k[(v >> 12) & 63]);
        out.push_back(k[(v >> 6) & 63]);
    }
    return out;
}

inline std::string b64url(const std::string &s)
{
    return b64url(reinterpret_cast<const unsigned char *>(s.data()), s.size());
}

inline std::string b64urlDecode(const std::string &s)
{
    std::string out;
    uint32_t acc = 0;
    int bits = 0;
    for (char c : s)
    {
        int v;
        if (c >= 'A' && c <= 'Z')
            v = c - 'A';
        else if (c >= 'a' && c <= 'z')
            v = c - 'a' + 26;
        else if (c >= '0' && c <= '9')
            v = c - '0' + 52;
        else if (c == '-')
            v = 62;
        else if (c == '_')
            v = 63;
        else
            return "";
        acc = (acc << 6) | v;
        bits += 6;
        if (bits >= 8)
        {
            bits -= 8;
            out.push_back(static_cast<char>((acc >> bits) & 0xFF));
        }
    }
    return out;
}

inline std::string hmacSha256(const std::string &key, const std::string &data)
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

inline const std::string &jwtSecret()
{
    static const std::string secret = []() {
        const char *e = std::getenv("JWT_SECRET");
        return (e && *e) ? std::string(e) : randomHex(32);
    }();
    return secret;
}

inline std::string makeToken(int64_t userId)
{
    auto exp = std::chrono::duration_cast<std::chrono::seconds>(
                   std::chrono::system_clock::now().time_since_epoch())
                   .count() +
               30L * 24 * 3600;
    std::string head = b64url(std::string(R"({"alg":"HS256","typ":"JWT"})"));
    std::string body = b64url("{\"id\":" + std::to_string(userId) +
                              ",\"exp\":" + std::to_string(exp) + "}");
    std::string msg = head + "." + body;
    return msg + "." + b64url(hmacSha256(jwtSecret(), msg));
}

// Returns the user id, or 0 if the token is invalid or expired.
inline int64_t verifyToken(const std::string &token)
{
    auto p1 = token.find('.');
    if (p1 == std::string::npos)
        return 0;
    auto p2 = token.find('.', p1 + 1);
    if (p2 == std::string::npos)
        return 0;
    std::string msg = token.substr(0, p2);
    std::string sig = b64urlDecode(token.substr(p2 + 1));
    std::string expect = hmacSha256(jwtSecret(), msg);
    if (sig.size() != expect.size() ||
        CRYPTO_memcmp(sig.data(), expect.data(), sig.size()) != 0)
        return 0;
    Json::Value payload;
    Json::CharReaderBuilder rb;
    std::string payloadStr = b64urlDecode(token.substr(p1 + 1, p2 - p1 - 1));
    std::string errs;
    std::unique_ptr<Json::CharReader> reader(rb.newCharReader());
    if (!reader->parse(payloadStr.data(),
                       payloadStr.data() + payloadStr.size(),
                       &payload,
                       &errs) ||
        !payload.isObject() || !payload["id"].isIntegral() ||
        !payload["exp"].isIntegral())
        return 0;
    auto now = std::chrono::duration_cast<std::chrono::seconds>(
                   std::chrono::system_clock::now().time_since_epoch())
                   .count();
    if (payload["exp"].asInt64() < now)
        return 0;
    return payload["id"].asInt64();
}

// ---------- responses ----------

inline drogon::HttpResponsePtr jsonResponse(const Json::Value &v,
                                            drogon::HttpStatusCode code = drogon::k200OK)
{
    auto resp = drogon::HttpResponse::newHttpJsonResponse(v);
    resp->setStatusCode(code);
    return resp;
}

inline drogon::HttpResponsePtr errorResponse(drogon::HttpStatusCode code,
                                             const std::string &field,
                                             const std::string &msg)
{
    Json::Value e(Json::objectValue);
    e["errors"][field].append(msg);
    return jsonResponse(e, code);
}

}  // namespace rw
