#pragma once

#include "common.h"

struct ArticleRef
{
    int64_t id;
    int64_t authorId;
};

// Looks up the article id and author id for a slug.
Task<std::optional<ArticleRef>> findArticleRef(const std::string &slug);

// Builds a profile JSON object for the given author columns.
Json::Value authorJson(const drogon::orm::Row &row,
                       const char *usernameCol,
                       const char *bioCol,
                       const char *imageCol,
                       const char *followingCol);
