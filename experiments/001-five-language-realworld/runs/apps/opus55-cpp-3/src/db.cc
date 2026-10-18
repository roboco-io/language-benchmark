#include "db.h"

#include <atomic>
#include <vector>

namespace db {
namespace {
std::vector<drogon::orm::DbClientPtr> pool;
std::atomic<size_t> next{0};

const char *kSchema[] = {
    "CREATE TABLE IF NOT EXISTS users ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " username TEXT NOT NULL UNIQUE,"
    " email TEXT NOT NULL UNIQUE,"
    " password_hash TEXT NOT NULL,"
    " bio TEXT,"
    " image TEXT)",
    "CREATE TABLE IF NOT EXISTS follows ("
    " follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " PRIMARY KEY (follower_id, followee_id))",
    "CREATE TABLE IF NOT EXISTS articles ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " slug TEXT NOT NULL UNIQUE,"
    " title TEXT NOT NULL,"
    " description TEXT NOT NULL,"
    " body TEXT NOT NULL,"
    " author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " created_at TEXT NOT NULL,"
    " updated_at TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id)",
    "CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at)",
    "CREATE TABLE IF NOT EXISTS article_tags ("
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " tag TEXT NOT NULL,"
    " position INTEGER NOT NULL,"
    " PRIMARY KEY (article_id, tag))",
    "CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag)",
    "CREATE TABLE IF NOT EXISTS favorites ("
    " user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " PRIMARY KEY (user_id, article_id))",
    "CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id)",
    "CREATE TABLE IF NOT EXISTS comments ("
    " id INTEGER PRIMARY KEY AUTOINCREMENT,"
    " article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,"
    " author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
    " body TEXT NOT NULL,"
    " created_at TEXT NOT NULL,"
    " updated_at TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)",
};
}  // namespace

void init(const std::string &path, size_t clients) {
  if (clients == 0) clients = 1;
  for (size_t i = 0; i < clients; ++i) {
    auto client = drogon::orm::DbClient::newSqlite3Client("filename=" + path, 1);
    client->execSqlSync("PRAGMA busy_timeout = 5000");
    client->execSqlSync("PRAGMA journal_mode = WAL");
    client->execSqlSync("PRAGMA synchronous = NORMAL");
    client->execSqlSync("PRAGMA foreign_keys = ON");
    pool.push_back(client);
  }
  for (const char *stmt : kSchema) pool.front()->execSqlSync(stmt);
}

drogon::orm::DbClientPtr get() {
  return pool[next.fetch_add(1, std::memory_order_relaxed) % pool.size()];
}

}  // namespace db
