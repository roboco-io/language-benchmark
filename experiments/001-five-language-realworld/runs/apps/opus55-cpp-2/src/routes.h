#pragma once

namespace rw
{
// Registers /api/users, /api/user and /api/profiles endpoints.
void registerUserRoutes();
// Registers /api/articles, comments, favorites and /api/tags endpoints.
void registerArticleRoutes();
}  // namespace rw
