#include "auth.h"

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>

#include <cstdlib>
#include <cstring>
#include <vector>

namespace
{
constexpr int kIterations = 10000;
constexpr int64_t kTokenLifetimeSecs = 30LL * 24 * 3600;

const std::string &secret()
{
    static const std::string value = [] {
        const char *env = std::getenv("JWT_SECRET");
        return std::string(env && *env ? env : "realworld-dev-secret-change-me");
    }();
    return value;
}

std::string toHex(const unsigned char *data, size_t len)
{
    static const char *digits = "0123456789abcdef";
    std::string out;
    for (size_t i = 0; i < len; ++i)
    {
        out.push_back(digits[data[i] >> 4]);
        out.push_back(digits[data[i] & 0xf]);
    }
    return out;
}

std::string pbkdf2(const std::string &password, const std::string &salt, int iterations)
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

const char *kB64 =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

std::string base64UrlEncode(const std::string &in)
{
    std::string out;
    size_t i = 0;
    while (i + 2 < in.size())
    {
        uint32_t n = (uint8_t(in[i]) << 16) | (uint8_t(in[i + 1]) << 8) |
                     uint8_t(in[i + 2]);
        out += kB64[(n >> 18) & 63];
        out += kB64[(n >> 12) & 63];
        out += kB64[(n >> 6) & 63];
        out += kB64[n & 63];
        i += 3;
    }
    size_t rest = in.size() - i;
    if (rest == 1)
    {
        uint32_t n = uint8_t(in[i]) << 16;
        out += kB64[(n >> 18) & 63];
        out += kB64[(n >> 12) & 63];
    }
    else if (rest == 2)
    {
        uint32_t n = (uint8_t(in[i]) << 16) | (uint8_t(in[i + 1]) << 8);
        out += kB64[(n >> 18) & 63];
        out += kB64[(n >> 12) & 63];
        out += kB64[(n >> 6) & 63];
    }
    return out;
}

std::optional<std::string> base64UrlDecode(const std::string &in)
{
    std::string out;
    uint32_t buf = 0;
    int bits = 0;
    for (char c : in)
    {
        const char *p = std::strchr(kB64, c);
        if (c == '\0' || p == nullptr)
            return std::nullopt;
        buf = (buf << 6) | static_cast<uint32_t>(p - kB64);
        bits += 6;
        if (bits >= 8)
        {
            bits -= 8;
            out.push_back(static_cast<char>((buf >> bits) & 0xff));
        }
    }
    return out;
}

std::string hmacSha256(const std::string &data)
{
    unsigned char out[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    HMAC(EVP_sha256(),
         secret().data(),
         static_cast<int>(secret().size()),
         reinterpret_cast<const unsigned char *>(data.data()),
         data.size(),
         out,
         &len);
    return std::string(reinterpret_cast<char *>(out), len);
}
}  // namespace

std::string hashPassword(const std::string &password)
{
    std::string salt = randomHex(16);
    return "pbkdf2$" + std::to_string(kIterations) + "$" + salt + "$" +
           pbkdf2(password, salt, kIterations);
}

bool verifyPassword(const std::string &password, const std::string &stored)
{
    // Format: pbkdf2$<iterations>$<salt>$<hash>
    auto p1 = stored.find('$');
    auto p2 = stored.find('$', p1 + 1);
    auto p3 = stored.find('$', p2 + 1);
    if (p1 == std::string::npos || p2 == std::string::npos || p3 == std::string::npos)
        return false;
    int iterations = std::atoi(stored.substr(p1 + 1, p2 - p1 - 1).c_str());
    if (iterations <= 0)
        return false;
    std::string salt = stored.substr(p2 + 1, p3 - p2 - 1);
    std::string expected = stored.substr(p3 + 1);
    std::string actual = pbkdf2(password, salt, iterations);
    return actual.size() == expected.size() &&
           CRYPTO_memcmp(actual.data(), expected.data(), actual.size()) == 0;
}

std::string makeToken(int64_t userId)
{
    static const std::string header =
        base64UrlEncode(R"({"alg":"HS256","typ":"JWT"})");
    int64_t now = nowMillis() / 1000;
    std::string payload = "{\"sub\":" + std::to_string(userId) +
                          ",\"iat\":" + std::to_string(now) +
                          ",\"exp\":" + std::to_string(now + kTokenLifetimeSecs) + "}";
    std::string signingInput = header + "." + base64UrlEncode(payload);
    return signingInput + "." + base64UrlEncode(hmacSha256(signingInput));
}

std::optional<int64_t> verifyToken(const std::string &token)
{
    auto d1 = token.find('.');
    if (d1 == std::string::npos)
        return std::nullopt;
    auto d2 = token.find('.', d1 + 1);
    if (d2 == std::string::npos || token.find('.', d2 + 1) != std::string::npos)
        return std::nullopt;

    std::string signingInput = token.substr(0, d2);
    std::string expectedSig = base64UrlEncode(hmacSha256(signingInput));
    std::string sig = token.substr(d2 + 1);
    if (sig.size() != expectedSig.size() ||
        CRYPTO_memcmp(sig.data(), expectedSig.data(), sig.size()) != 0)
        return std::nullopt;

    auto payload = base64UrlDecode(token.substr(d1 + 1, d2 - d1 - 1));
    if (!payload)
        return std::nullopt;
    Json::Value claims;
    Json::CharReaderBuilder builder;
    std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
    std::string errs;
    if (!reader->parse(payload->data(),
                       payload->data() + payload->size(),
                       &claims,
                       &errs) ||
        !claims.isObject() || !claims["sub"].isIntegral() ||
        !claims["exp"].isIntegral())
        return std::nullopt;
    if (claims["exp"].asInt64() < nowMillis() / 1000)
        return std::nullopt;
    return claims["sub"].asInt64();
}

AuthResult authenticate(const HttpRequestPtr &req, bool required)
{
    AuthResult result;
    const std::string &header = req->getHeader("authorization");
    if (header.empty())
    {
        if (required)
            result.error =
                errorResponse(drogon::k401Unauthorized, "token", "is missing");
        return result;
    }
    std::string token;
    if (header.rfind("Token ", 0) == 0)
        token = header.substr(6);
    else if (header.rfind("Bearer ", 0) == 0)
        token = header.substr(7);
    auto userId = token.empty() ? std::nullopt : verifyToken(token);
    if (!userId)
    {
        result.error = errorResponse(drogon::k401Unauthorized, "token", "is invalid");
        return result;
    }
    result.userId = userId;
    return result;
}
