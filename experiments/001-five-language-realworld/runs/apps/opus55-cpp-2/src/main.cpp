#include "auth.h"
#include "routes.h"
#include "util.h"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <thread>

using namespace drogon;

namespace
{
// Every statement is idempotent, so the schema is created on first start
// with an empty database and left unchanged afterwards.
const char *kSchema[] = {
    R"SQL(CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT NOT NULL UNIQUE,
        email TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        bio TEXT,
        image TEXT))SQL",
    R"SQL(CREATE TABLE IF NOT EXISTS follows (
        follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        PRIMARY KEY (follower_id, followee_id)))SQL",
    R"SQL(CREATE TABLE IF NOT EXISTS articles (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        slug TEXT NOT NULL UNIQUE,
        title TEXT NOT NULL,
        description TEXT NOT NULL,
        body TEXT NOT NULL,
        author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL))SQL",
    "CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id)",
    "CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at)",
    R"SQL(CREATE TABLE IF NOT EXISTS tags (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL UNIQUE))SQL",
    R"SQL(CREATE TABLE IF NOT EXISTS article_tags (
        article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
        tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
        position INTEGER NOT NULL,
        PRIMARY KEY (article_id, tag_id)))SQL",
    "CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag_id)",
    R"SQL(CREATE TABLE IF NOT EXISTS favorites (
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, article_id)))SQL",
    "CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id)",
    R"SQL(CREATE TABLE IF NOT EXISTS comments (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        body TEXT NOT NULL,
        article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
        author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL))SQL",
    "CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)",
};

std::string envOr(const char *name, const std::string &fallback)
{
    const char *v = std::getenv(name);
    return (v && *v) ? std::string(v) : fallback;
}
}  // namespace

int main()
{
    trantor::Logger::setLogLevel(trantor::Logger::kInfo);
    // Flush every log line so output is visible immediately when stdout is a
    // pipe (for example under Docker).
    trantor::Logger::setOutputFunction(
        [](const char *msg, uint64_t len) {
            std::fwrite(msg, 1, len, stdout);
            std::fflush(stdout);
        },
        []() { std::fflush(stdout); });
    const unsigned cores = std::max(1u, std::thread::hardware_concurrency());
    const int port = std::stoi(envOr("PORT", "8080"));
    const std::string dbPath = envOr("DATABASE_PATH", "realworld.db");

    auto parent = std::filesystem::path(dbPath).parent_path();
    if (!parent.empty())
        std::filesystem::create_directories(parent);

    // These statements run on every pooled connection before it is used.
    // busy_timeout comes first so that the remaining statements wait for
    // locks instead of failing while connections open concurrently.
    // busy_timeout and foreign_keys are per-connection settings; journal_mode
    // WAL is persisted in the database file.
    const std::vector<std::string> connectionInit = {
        "PRAGMA busy_timeout = 5000",
        "PRAGMA journal_mode = WAL",
        "PRAGMA foreign_keys = ON",
        "PRAGMA synchronous = NORMAL",
    };

    // Switch the file to WAL and create the schema on a single connection
    // first, so the pooled connections never compete for these locks.
    {
        auto bootstrap = orm::DbClient::newSqlite3Client("filename=" + dbPath,
                                                         1,
                                                         connectionInit);
        for (const char *stmt : kSchema)
            bootstrap->execSqlSync(stmt);
    }

    auto client = orm::DbClient::newSqlite3Client("filename=" + dbPath,
                                                  cores,
                                                  connectionInit);
    {
        auto mode = client->execSqlSync("PRAGMA journal_mode");
        auto timeout = client->execSqlSync("PRAGMA busy_timeout");
        LOG_INFO << "SQLite journal_mode=" << mode[0][0].as<std::string>()
                 << " busy_timeout=" << timeout[0][0].as<std::string>()
                 << "ms connections=" << cores;
    }
    rw::setDb(client);
    rw::initJwtSecret();

    rw::registerUserRoutes();
    rw::registerArticleRoutes();

    app().setExceptionHandler(
        [](const std::exception &e,
           const HttpRequestPtr &,
           std::function<void(const HttpResponsePtr &)> &&callback) {
            LOG_ERROR << "Unhandled exception: " << e.what();
            callback(rw::errorResponse(k500InternalServerError,
                                       "server",
                                       "internal error"));
        });

    LOG_INFO << "Listening on 0.0.0.0:" << port << " with " << cores
             << " threads, database " << dbPath;
    app()
        .setThreadNum(cores)
        .addListener("0.0.0.0", static_cast<uint16_t>(port))
        .run();
    return 0;
}
