// Generated from prisma/schema.prisma via `prisma migrate diff`; idempotent so it can run on every boot.
export const SCHEMA_STATEMENTS: string[] = [
  `CREATE TABLE IF NOT EXISTS "User" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "email" TEXT NOT NULL,
    "username" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "bio" TEXT,
    "image" TEXT
)`,
  `
CREATE TABLE IF NOT EXISTS "Article" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "slug" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL,
    "authorId" INTEGER NOT NULL,
    CONSTRAINT "Article_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE
)`,
  `
CREATE TABLE IF NOT EXISTS "ArticleTag" (
    "articleId" INTEGER NOT NULL,
    "name" TEXT NOT NULL,
    "position" INTEGER NOT NULL,

    PRIMARY KEY ("articleId", "name"),
    CONSTRAINT "ArticleTag_articleId_fkey" FOREIGN KEY ("articleId") REFERENCES "Article" ("id") ON DELETE CASCADE ON UPDATE CASCADE
)`,
  `
CREATE TABLE IF NOT EXISTS "Comment" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "body" TEXT NOT NULL,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL,
    "articleId" INTEGER NOT NULL,
    "authorId" INTEGER NOT NULL,
    CONSTRAINT "Comment_articleId_fkey" FOREIGN KEY ("articleId") REFERENCES "Article" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "Comment_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE
)`,
  `
CREATE TABLE IF NOT EXISTS "Favorite" (
    "userId" INTEGER NOT NULL,
    "articleId" INTEGER NOT NULL,

    PRIMARY KEY ("userId", "articleId"),
    CONSTRAINT "Favorite_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "Favorite_articleId_fkey" FOREIGN KEY ("articleId") REFERENCES "Article" ("id") ON DELETE CASCADE ON UPDATE CASCADE
)`,
  `
CREATE TABLE IF NOT EXISTS "Follow" (
    "followerId" INTEGER NOT NULL,
    "followedId" INTEGER NOT NULL,

    PRIMARY KEY ("followerId", "followedId"),
    CONSTRAINT "Follow_followerId_fkey" FOREIGN KEY ("followerId") REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "Follow_followedId_fkey" FOREIGN KEY ("followedId") REFERENCES "User" ("id") ON DELETE CASCADE ON UPDATE CASCADE
)`,
  `
CREATE UNIQUE INDEX IF NOT EXISTS "User_email_key" ON "User"("email")`,
  `
CREATE UNIQUE INDEX IF NOT EXISTS "User_username_key" ON "User"("username")`,
  `
CREATE UNIQUE INDEX IF NOT EXISTS "Article_slug_key" ON "Article"("slug")`,
  `
CREATE INDEX IF NOT EXISTS "Article_authorId_idx" ON "Article"("authorId")`,
  `
CREATE INDEX IF NOT EXISTS "Article_createdAt_idx" ON "Article"("createdAt")`,
  `
CREATE INDEX IF NOT EXISTS "ArticleTag_name_idx" ON "ArticleTag"("name")`,
  `
CREATE INDEX IF NOT EXISTS "Comment_articleId_idx" ON "Comment"("articleId")`,
  `
CREATE INDEX IF NOT EXISTS "Favorite_articleId_idx" ON "Favorite"("articleId")`,
  `
CREATE INDEX IF NOT EXISTS "Follow_followedId_idx" ON "Follow"("followedId")`,
];
