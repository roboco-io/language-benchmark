#include "common.h"

#include <sqlite3.h>

#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <thread>

namespace
{
const char *kSchema = R"(
PRAGMA journal_mode = WAL;
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  bio TEXT,
  image TEXT
);
CREATE TABLE IF NOT EXISTS follows (
  follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (follower_id, followee_id)
);
CREATE TABLE IF NOT EXISTS articles (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  slug TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  body TEXT NOT NULL,
  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id);
CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at);
CREATE TABLE IF NOT EXISTS article_tags (
  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
  tag TEXT NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (article_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag);
CREATE TABLE IF NOT EXISTS favorites (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, article_id)
);
CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id);
CREATE TABLE IF NOT EXISTS comments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  body TEXT NOT NULL,
  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id);
)";

// Creates the schema (if missing) and enables WAL mode, which is persistent
// in the database file. Uses the raw SQLite API so it runs before Drogon starts.
bool initSchema(const std::string &path)
{
    sqlite3 *conn = nullptr;
    if (sqlite3_open(path.c_str(), &conn) != SQLITE_OK)
    {
        std::cerr << "cannot open database " << path << ": " << sqlite3_errmsg(conn)
                  << "\n";
        sqlite3_close(conn);
        return false;
    }
    sqlite3_busy_timeout(conn, 5000);
    char *err = nullptr;
    bool ok = sqlite3_exec(conn, kSchema, nullptr, nullptr, &err) == SQLITE_OK;
    if (!ok)
    {
        std::cerr << "schema creation failed: " << (err ? err : "?") << "\n";
        sqlite3_free(err);
    }
    sqlite3_close(conn);
    // Drogon calls sqlite3_config() later, which only works while the
    // library is not initialized, so undo the implicit initialization.
    sqlite3_shutdown();
    return ok;
}

std::string envOr(const char *name, const std::string &fallback)
{
    const char *v = std::getenv(name);
    return v && *v ? std::string(v) : fallback;
}
}  // namespace

int main()
{
    int port = std::atoi(envOr("PORT", "8080").c_str());
    std::string dbPath = envOr("DATABASE_PATH", "realworld.db");
    size_t cores = std::max(1u, std::thread::hardware_concurrency());

    auto parent = std::filesystem::path(dbPath).parent_path();
    if (!parent.empty())
        std::filesystem::create_directories(parent);
    if (!initSchema(dbPath))
        return 1;

    // Every pooled connection runs these statements when it opens. The busy
    // timeout comes first so connections that open in parallel wait for each
    // other's locks instead of failing.
    setDb(drogon::orm::DbClient::newSqlite3Client(
        "filename=" + dbPath,
        cores,
        {"PRAGMA busy_timeout = 5000",
         "PRAGMA journal_mode = WAL",
         "PRAGMA foreign_keys = ON"}));

    registerUserRoutes();
    registerProfileRoutes();
    registerArticleRoutes();
    registerCommentRoutes();

    std::cout << "RealWorld API listening on port " << port << " with " << cores
              << " threads, database " << dbPath << std::endl;
    drogon::app()
        .setThreadNum(cores)
        .setLogLevel(trantor::Logger::kWarn)
        .addListener("0.0.0.0", static_cast<uint16_t>(port))
        .run();
    return 0;
}
