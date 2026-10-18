// RealWorld backend: Drogon + SQLite.
#include <drogon/drogon.h>
#include <sqlite3.h>

#include <cstdio>
#include <thread>

#include "api.h"
#include "util.h"

namespace
{
const char *kSchema = R"SQL(
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  email TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  bio TEXT,
  image TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS articles (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  slug TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  body TEXT NOT NULL,
  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id);
CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at DESC, id DESC);
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
CREATE TABLE IF NOT EXISTS follows (
  follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  followed_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (follower_id, followed_id)
);
CREATE INDEX IF NOT EXISTS idx_follows_followed ON follows(followed_id);
CREATE TABLE IF NOT EXISTS comments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id);
)SQL";

// Creates the database file and schema on first start; enables WAL.
bool initDatabase(const std::string &path)
{
    sqlite3 *db = nullptr;
    if (sqlite3_open(path.c_str(), &db) != SQLITE_OK)
    {
        std::fprintf(stderr, "cannot open database %s: %s\n", path.c_str(),
                     db ? sqlite3_errmsg(db) : "?");
        return false;
    }
    sqlite3_busy_timeout(db, 5000);
    char *err = nullptr;
    std::string sql = std::string("PRAGMA journal_mode=WAL;") + kSchema;
    int rc = sqlite3_exec(db, sql.c_str(), nullptr, nullptr, &err);
    if (rc != SQLITE_OK)
    {
        std::fprintf(stderr, "schema init failed: %s\n", err ? err : "?");
        sqlite3_free(err);
    }
    sqlite3_close(db);
    // Drogon calls sqlite3_config() itself, which is only allowed while SQLite is uninitialized.
    sqlite3_shutdown();
    return rc == SQLITE_OK;
}
}  // namespace

int main()
{
    const std::string dbPath = util::envOr("DB_PATH", "realworld.db");
    const int port = std::atoi(util::envOr("PORT", "8080").c_str());
    size_t cores = std::thread::hardware_concurrency();
    if (cores == 0) cores = 1;

    if (!initDatabase(dbPath)) return 1;

    using namespace drogon;
    app().setThreadNum(cores);
    app().setLogLevel(trantor::Logger::kWarn);

    orm::Sqlite3Config cfg;
    cfg.connectionNumber = cores;
    cfg.filename = dbPath;
    cfg.name = "default";
    cfg.timeout = -1.0;
    // These run on every pooled connection; busy_timeout is per connection.
    cfg.initializationQueries = {"PRAGMA busy_timeout=5000", "PRAGMA journal_mode=WAL",
                                 "PRAGMA synchronous=NORMAL", "PRAGMA foreign_keys=ON"};
    app().addDbClient(cfg);

    registerRoutes();
    app().addListener("0.0.0.0", (uint16_t)port);
    LOG_WARN << "RealWorld backend listening on port " << port << " with " << cores
             << " threads, db=" << dbPath;
    app().run();
    return 0;
}
