#include "util.h"

#include <drogon/orm/Field.h>
#include <openssl/rand.h>

#include <chrono>
#include <ctime>

#include "auth.h"
#include "db.h"

void sendJson(const Callback &cb, const Json::Value &body, drogon::HttpStatusCode code) {
  auto resp = drogon::HttpResponse::newHttpJsonResponse(body);
  resp->setStatusCode(code);
  cb(resp);
}

void sendError(const Callback &cb, drogon::HttpStatusCode code, const std::string &field,
               const std::string &message) {
  Json::Value body;
  body["errors"][field].append(message);
  sendJson(cb, body, code);
}

void sendNoContent(const Callback &cb) {
  auto resp = drogon::HttpResponse::newHttpResponse();
  resp->setStatusCode(drogon::k204NoContent);
  cb(resp);
}

AuthResult authenticate(const drogon::HttpRequestPtr &req) {
  AuthResult result;
  const std::string &header = req->getHeader("authorization");
  if (header.empty()) return result;
  std::string token;
  if (header.rfind("Token ", 0) == 0) {
    token = header.substr(6);
  } else if (header.rfind("Bearer ", 0) == 0) {
    token = header.substr(7);
  } else {
    result.state = AuthResult::State::Invalid;
    return result;
  }
  auto id = auth::verifyToken(token);
  if (!id) {
    result.state = AuthResult::State::Invalid;
    return result;
  }
  auto rows = db::get()->execSqlSync("SELECT 1 FROM users WHERE id = ?", *id);
  if (rows.empty()) {
    result.state = AuthResult::State::Invalid;
    return result;
  }
  result.state = AuthResult::State::Ok;
  result.userId = *id;
  return result;
}

std::optional<int64_t> requireUser(const drogon::HttpRequestPtr &req, const Callback &cb) {
  auto a = authenticate(req);
  if (a.state == AuthResult::State::Ok) return a.userId;
  sendError(cb, drogon::k401Unauthorized, "token",
            a.state == AuthResult::State::Missing ? "is missing" : "is invalid");
  return std::nullopt;
}

std::optional<int64_t> optionalUser(const drogon::HttpRequestPtr &req, const Callback &cb) {
  auto a = authenticate(req);
  if (a.state == AuthResult::State::Ok) return a.userId;
  if (a.state == AuthResult::State::Missing) return int64_t{0};
  sendError(cb, drogon::k401Unauthorized, "token", "is invalid");
  return std::nullopt;
}

std::string nowIso() {
  using namespace std::chrono;
  auto now = system_clock::now();
  auto ms = duration_cast<milliseconds>(now.time_since_epoch()).count() % 1000;
  std::time_t t = system_clock::to_time_t(now);
  std::tm tm{};
  gmtime_r(&t, &tm);
  char buf[32];
  std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S", &tm);
  char out[40];
  std::snprintf(out, sizeof(out), "%s.%03dZ", buf, static_cast<int>(ms));
  return out;
}

std::string slugify(const std::string &title) {
  std::string out;
  bool dash = false;
  for (unsigned char c : title) {
    if (std::isalnum(c)) {
      out += static_cast<char>(std::tolower(c));
      dash = false;
    } else if (!out.empty() && !dash) {
      out += '-';
      dash = true;
    }
  }
  while (!out.empty() && out.back() == '-') out.pop_back();
  if (out.empty()) out = "article";
  return out;
}

std::string randomSuffix() {
  static const char *digits = "0123456789abcdefghijklmnopqrstuvwxyz";
  unsigned char bytes[8];
  RAND_bytes(bytes, sizeof(bytes));
  std::string out;
  for (unsigned char b : bytes) out += digits[b % 36];
  return out;
}

Json::Value nullableString(const drogon::orm::Field &field) {
  if (field.isNull()) return Json::Value();
  auto s = field.as<std::string>();
  if (s.empty()) return Json::Value();
  return s;
}
