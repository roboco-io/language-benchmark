#pragma once
#include <drogon/orm/DbClient.h>
#include <string>

namespace db {

// Opens one single-connection client per worker so that per-connection
// PRAGMAs (busy_timeout, foreign_keys) are guaranteed to apply everywhere,
// then creates the schema if it does not exist yet.
void init(const std::string &path, size_t clients);

// Returns a client from the pool (round-robin).
drogon::orm::DbClientPtr get();

}  // namespace db
