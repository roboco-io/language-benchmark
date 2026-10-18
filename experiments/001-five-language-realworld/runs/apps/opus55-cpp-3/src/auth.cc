#include "auth.h"

#include <json/json.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

#include <chrono>
#include <sstream>
#include <vector>

namespace auth {
namespace {

std::string secretKey = "realworld-dev-secret";
constexpr int kIterations = 10000;
constexpr int64_t kTokenTtlSeconds = 60 * 60 * 24 * 30;

std::string b64urlEncode(const unsigned char *data, size_t len) {
  static const char *tbl =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  std::string out;
  out.reserve((len + 2) / 3 * 4);
  size_t i = 0;
  for (; i + 2 < len; i += 3) {
    uint32_t v = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
    out += tbl[(v >> 18) & 63];
    out += tbl[(v >> 12) & 63];
    out += tbl[(v >> 6) & 63];
    out += tbl[v & 63];
  }
  if (i + 1 == len) {
    uint32_t v = data[i] << 16;
    out += tbl[(v >> 18) & 63];
    out += tbl[(v >> 12) & 63];
  } else if (i + 2 == len) {
    uint32_t v = (data[i] << 16) | (data[i + 1] << 8);
    out += tbl[(v >> 18) & 63];
    out += tbl[(v >> 12) & 63];
    out += tbl[(v >> 6) & 63];
  }
  return out;
}

std::string b64urlEncode(const std::string &s) {
  return b64urlEncode(reinterpret_cast<const unsigned char *>(s.data()), s.size());
}

std::optional<std::string> b64urlDecode(const std::string &s) {
  auto val = [](char c) -> int {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-') return 62;
    if (c == '_') return 63;
    return -1;
  };
  std::string out;
  uint32_t buf = 0;
  int bits = 0;
  for (char c : s) {
    int v = val(c);
    if (v < 0) return std::nullopt;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += static_cast<char>((buf >> bits) & 0xFF);
    }
  }
  return out;
}

std::string hmacSha256(const std::string &data) {
  unsigned char mac[EVP_MAX_MD_SIZE];
  unsigned int len = 0;
  HMAC(EVP_sha256(), secretKey.data(), static_cast<int>(secretKey.size()),
       reinterpret_cast<const unsigned char *>(data.data()), data.size(), mac, &len);
  return b64urlEncode(mac, len);
}

std::string hex(const unsigned char *data, size_t len) {
  static const char *digits = "0123456789abcdef";
  std::string out;
  for (size_t i = 0; i < len; ++i) {
    out += digits[data[i] >> 4];
    out += digits[data[i] & 15];
  }
  return out;
}

std::string pbkdf2(const std::string &password, const std::string &salt, int iterations) {
  unsigned char out[32];
  PKCS5_PBKDF2_HMAC(password.data(), static_cast<int>(password.size()),
                    reinterpret_cast<const unsigned char *>(salt.data()),
                    static_cast<int>(salt.size()), iterations, EVP_sha256(), sizeof(out), out);
  return hex(out, sizeof(out));
}

int64_t nowSeconds() {
  return std::chrono::duration_cast<std::chrono::seconds>(
             std::chrono::system_clock::now().time_since_epoch())
      .count();
}

}  // namespace

void setSecret(const std::string &secret) {
  if (!secret.empty()) secretKey = secret;
}

std::string makeToken(int64_t userId) {
  static const std::string header = b64urlEncode(R"({"alg":"HS256","typ":"JWT"})");
  int64_t now = nowSeconds();
  std::string payload = "{\"sub\":" + std::to_string(userId) + ",\"iat\":" +
                        std::to_string(now) + ",\"exp\":" +
                        std::to_string(now + kTokenTtlSeconds) + "}";
  std::string signingInput = header + "." + b64urlEncode(payload);
  return signingInput + "." + hmacSha256(signingInput);
}

std::optional<int64_t> verifyToken(const std::string &token) {
  auto dot1 = token.find('.');
  if (dot1 == std::string::npos) return std::nullopt;
  auto dot2 = token.find('.', dot1 + 1);
  if (dot2 == std::string::npos) return std::nullopt;
  std::string signingInput = token.substr(0, dot2);
  std::string expected = hmacSha256(signingInput);
  std::string given = token.substr(dot2 + 1);
  if (expected.size() != given.size() ||
      CRYPTO_memcmp(expected.data(), given.data(), expected.size()) != 0)
    return std::nullopt;
  auto payload = b64urlDecode(token.substr(dot1 + 1, dot2 - dot1 - 1));
  if (!payload) return std::nullopt;
  Json::Value json;
  Json::CharReaderBuilder builder;
  std::string errs;
  std::istringstream in(*payload);
  if (!Json::parseFromStream(builder, in, &json, &errs) || !json.isObject())
    return std::nullopt;
  if (!json["sub"].isIntegral()) return std::nullopt;
  if (json["exp"].isIntegral() && json["exp"].asInt64() < nowSeconds()) return std::nullopt;
  return json["sub"].asInt64();
}

std::string hashPassword(const std::string &password) {
  unsigned char saltBytes[16];
  RAND_bytes(saltBytes, sizeof(saltBytes));
  std::string salt = hex(saltBytes, sizeof(saltBytes));
  return std::to_string(kIterations) + "$" + salt + "$" + pbkdf2(password, salt, kIterations);
}

bool checkPassword(const std::string &password, const std::string &stored) {
  auto d1 = stored.find('$');
  auto d2 = stored.find('$', d1 == std::string::npos ? 0 : d1 + 1);
  if (d1 == std::string::npos || d2 == std::string::npos) return false;
  int iterations = std::stoi(stored.substr(0, d1));
  std::string salt = stored.substr(d1 + 1, d2 - d1 - 1);
  std::string expected = stored.substr(d2 + 1);
  std::string actual = pbkdf2(password, salt, iterations);
  return actual.size() == expected.size() &&
         CRYPTO_memcmp(actual.data(), expected.data(), actual.size()) == 0;
}

}  // namespace auth
