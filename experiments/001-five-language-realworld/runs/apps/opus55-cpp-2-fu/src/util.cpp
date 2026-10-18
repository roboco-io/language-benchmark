#include "util.h"

#include <openssl/rand.h>

#include <chrono>
#include <cstdio>
#include <ctime>
#include <stdexcept>

namespace rw
{
namespace
{
drogon::orm::DbClientPtr gDb;
}

drogon::orm::DbClientPtr db()
{
    return gDb;
}

void setDb(drogon::orm::DbClientPtr client)
{
    gDb = std::move(client);
}

drogon::HttpResponsePtr jsonResponse(const Json::Value &body,
                                     drogon::HttpStatusCode code)
{
    auto resp = drogon::HttpResponse::newHttpJsonResponse(body);
    resp->setStatusCode(code);
    return resp;
}

drogon::HttpResponsePtr errorResponse(drogon::HttpStatusCode code,
                                      const std::string &field,
                                      const std::string &message)
{
    Json::Value body;
    body["errors"][field].append(message);
    return jsonResponse(body, code);
}

std::optional<Json::Value> parseBody(const drogon::HttpRequestPtr &req)
{
    auto body = req->body();
    if (body.empty())
        return std::nullopt;
    Json::Value root;
    Json::CharReaderBuilder builder;
    std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
    std::string errs;
    if (!reader->parse(body.data(), body.data() + body.size(), &root, &errs))
        return std::nullopt;
    return root;
}

std::string nowIso8601()
{
    using namespace std::chrono;
    auto now = system_clock::now();
    auto ms = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
    std::time_t t = system_clock::to_time_t(now);
    std::tm tm{};
    gmtime_r(&t, &tm);
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
                  static_cast<int>(ms.count()));
    return buf;
}

std::string randomHex(size_t bytes)
{
    std::string raw(bytes, '\0');
    if (RAND_bytes(reinterpret_cast<unsigned char *>(raw.data()),
                   static_cast<int>(bytes)) != 1)
        throw std::runtime_error("RAND_bytes failed");
    static const char *digits = "0123456789abcdef";
    std::string out;
    out.reserve(bytes * 2);
    for (unsigned char c : raw)
    {
        out.push_back(digits[c >> 4]);
        out.push_back(digits[c & 0x0f]);
    }
    return out;
}

std::string makeSlug(const std::string &title)
{
    std::string slug;
    bool pendingDash = false;
    for (unsigned char c : title)
    {
        if (std::isalnum(c))
        {
            if (pendingDash && !slug.empty())
                slug.push_back('-');
            pendingDash = false;
            slug.push_back(static_cast<char>(std::tolower(c)));
        }
        else
        {
            pendingDash = true;
        }
        if (slug.size() >= 80)
            break;
    }
    if (slug.empty())
        slug = "article";
    // The random suffix keeps slugs unique when titles repeat and avoids
    // collisions with reserved paths such as /api/articles/feed.
    return slug + "-" + randomHex(4);
}

Json::Value nullableString(const drogon::orm::Field &field)
{
    if (field.isNull())
        return Json::Value(Json::nullValue);
    return Json::Value(field.as<std::string>());
}
}  // namespace rw
