#include <drogon/drogon.h>

#include <cstdlib>
#include <thread>

#include "auth.h"
#include "db.h"
#include "handlers.h"

namespace {
std::string env(const char *name, const std::string &def) {
  const char *v = std::getenv(name);
  return (v && *v) ? std::string(v) : def;
}
}  // namespace

int main() {
  unsigned threads = std::thread::hardware_concurrency();
  if (threads == 0) threads = 1;
  int port = std::stoi(env("PORT", "8000"));

  auth::setSecret(env("JWT_SECRET", ""));
  db::init(env("DATABASE_PATH", "realworld.db"), threads);

  registerUserRoutes();
  registerArticleRoutes();

  drogon::app().setExceptionHandler(
      [](const std::exception &e, const drogon::HttpRequestPtr &, Callback &&cb) {
        LOG_ERROR << "Unhandled exception: " << e.what();
        sendError(cb, drogon::k500InternalServerError, "server", "internal error");
      });

  LOG_INFO << "Listening on port " << port << " with " << threads << " threads";
  drogon::app()
      .setThreadNum(threads)
      .setLogLevel(trantor::Logger::kWarn)
      .addListener("0.0.0.0", port)
      .run();
}
