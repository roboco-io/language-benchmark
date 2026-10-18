#include "common.h"

#include <openssl/rand.h>

#include <cctype>
#include <chrono>
#include <cstdio>
#include <ctime>

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

int64_t nowMillis()
{
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch())
        .count();
}

std::string isoTime(int64_t millis)
{
    std::time_t secs = static_cast<std::time_t>(millis / 1000);
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
                  static_cast<int>(millis % 1000));
    return buf;
}

std::shared_ptr<Json::Value> parseBody(const HttpRequestPtr &req)
{
    auto body = req->body();
    auto value = std::make_shared<Json::Value>();
    Json::CharReaderBuilder builder;
    std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
    std::string errs;
    if (body.empty() ||
        !reader->parse(body.data(), body.data() + body.size(), value.get(), &errs))
        return nullptr;
    return value;
}

HttpResponsePtr jsonResponse(const Json::Value &body, drogon::HttpStatusCode code)
{
    auto resp = drogon::HttpResponse::newHttpJsonResponse(body);
    resp->setStatusCode(code);
    return resp;
}

HttpResponsePtr errorResponse(drogon::HttpStatusCode code,
                              const std::string &field,
                              const std::string &message)
{
    Json::Value body;
    body["errors"][field].append(message);
    return jsonResponse(body, code);
}

HttpResponsePtr validationResponse(const Json::Value &errors)
{
    Json::Value body;
    body["errors"] = errors;
    return jsonResponse(body, drogon::k422UnprocessableEntity);
}

HttpResponsePtr noContent()
{
    auto resp = drogon::HttpResponse::newHttpResponse();
    resp->setStatusCode(drogon::k204NoContent);
    return resp;
}

bool isNonEmptyString(const Json::Value &v)
{
    return v.isString() && !v.asString().empty();
}

std::string slugify(const std::string &title)
{
    std::string slug;
    bool dash = false;
    for (unsigned char c : title)
    {
        if (std::isalnum(c))
        {
            if (dash && !slug.empty())
                slug.push_back('-');
            slug.push_back(static_cast<char>(std::tolower(c)));
            dash = false;
        }
        else
        {
            dash = true;
        }
    }
    if (slug.empty())
        slug = "article";
    return slug;
}

std::string randomHex(size_t bytes)
{
    std::string raw(bytes, '\0');
    RAND_bytes(reinterpret_cast<unsigned char *>(raw.data()),
               static_cast<int>(bytes));
    static const char *digits = "0123456789abcdef";
    std::string out;
    for (unsigned char c : raw)
    {
        out.push_back(digits[c >> 4]);
        out.push_back(digits[c & 0xf]);
    }
    return out;
}

Json::Value nullableText(const drogon::orm::Field &f)
{
    if (f.isNull())
        return Json::Value();
    return Json::Value(f.as<std::string>());
}
