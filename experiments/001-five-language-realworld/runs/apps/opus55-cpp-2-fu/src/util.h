#pragma once

#include <drogon/drogon.h>
#include <drogon/utils/coroutine.h>
#include <json/json.h>

#include <optional>
#include <string>

namespace rw
{
// Shared database client (a pool of SQLite connections).
drogon::orm::DbClientPtr db();
void setDb(drogon::orm::DbClientPtr client);

// Builds a JSON response with the given status code.
drogon::HttpResponsePtr jsonResponse(const Json::Value &body,
                                     drogon::HttpStatusCode code = drogon::k200OK);

// Builds a RealWorld error response: {"errors": {field: [message]}}.
drogon::HttpResponsePtr errorResponse(drogon::HttpStatusCode code,
                                      const std::string &field,
                                      const std::string &message);

// Parses the request body as JSON. Returns std::nullopt for invalid JSON.
std::optional<Json::Value> parseBody(const drogon::HttpRequestPtr &req);

// Current UTC time in ISO 8601 format with millisecond precision.
std::string nowIso8601();

// Converts a title into a URL-friendly slug with a random unique suffix.
std::string makeSlug(const std::string &title);

// Returns a lower-case hex string of `bytes` cryptographically random bytes.
std::string randomHex(size_t bytes);

// Awaits the commit of a transaction. Drogon commits a transaction when its
// last reference is released; this awaiter releases it and resumes the
// coroutine after the commit has finished, so later reads on other pooled
// connections see the changes. Pass the only reference via std::move.
struct CommitAwaiter : public drogon::CallbackAwaiter<bool>
{
    explicit CommitAwaiter(std::shared_ptr<drogon::orm::Transaction> tx)
        : tx_(std::move(tx))
    {
    }

    void await_suspend(std::coroutine_handle<> handle)
    {
        tx_->setCommitCallback([this, handle](bool ok) {
            setValue(ok);
            handle.resume();
        });
        tx_.reset();
    }

  private:
    std::shared_ptr<drogon::orm::Transaction> tx_;
};

// Converts a nullable text column into a JSON value (null or string).
Json::Value nullableString(const drogon::orm::Field &field);
}  // namespace rw
