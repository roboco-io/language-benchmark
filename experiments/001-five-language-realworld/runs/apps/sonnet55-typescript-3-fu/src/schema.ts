// Generated from prisma/schema.prisma (prisma migrate diff). Idempotent DDL so an empty database
// gets its schema at startup without needing the Prisma CLI at runtime.
export const SCHEMA_STATEMENTS: string[] = [
  "CREATE TABLE IF NOT EXISTS \"User\" (\n    \"id\" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n    \"email\" TEXT NOT NULL,\n    \"username\" TEXT NOT NULL,\n    \"password\" TEXT NOT NULL,\n    \"bio\" TEXT,\n    \"image\" TEXT\n)",
  "CREATE TABLE IF NOT EXISTS \"Article\" (\n    \"id\" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n    \"slug\" TEXT NOT NULL,\n    \"title\" TEXT NOT NULL,\n    \"description\" TEXT NOT NULL,\n    \"body\" TEXT NOT NULL,\n    \"createdAt\" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n    \"updatedAt\" DATETIME NOT NULL,\n    \"authorId\" INTEGER NOT NULL,\n    CONSTRAINT \"Article_authorId_fkey\" FOREIGN KEY (\"authorId\") REFERENCES \"User\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE\n)",
  "CREATE TABLE IF NOT EXISTS \"Tag\" (\n    \"id\" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n    \"name\" TEXT NOT NULL\n)",
  "CREATE TABLE IF NOT EXISTS \"ArticleTag\" (\n    \"articleId\" INTEGER NOT NULL,\n    \"tagId\" INTEGER NOT NULL,\n\n    PRIMARY KEY (\"articleId\", \"tagId\"),\n    CONSTRAINT \"ArticleTag_articleId_fkey\" FOREIGN KEY (\"articleId\") REFERENCES \"Article\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE,\n    CONSTRAINT \"ArticleTag_tagId_fkey\" FOREIGN KEY (\"tagId\") REFERENCES \"Tag\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE\n)",
  "CREATE TABLE IF NOT EXISTS \"Comment\" (\n    \"id\" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,\n    \"body\" TEXT NOT NULL,\n    \"createdAt\" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n    \"updatedAt\" DATETIME NOT NULL,\n    \"articleId\" INTEGER NOT NULL,\n    \"authorId\" INTEGER NOT NULL,\n    CONSTRAINT \"Comment_articleId_fkey\" FOREIGN KEY (\"articleId\") REFERENCES \"Article\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE,\n    CONSTRAINT \"Comment_authorId_fkey\" FOREIGN KEY (\"authorId\") REFERENCES \"User\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE\n)",
  "CREATE TABLE IF NOT EXISTS \"Favorite\" (\n    \"userId\" INTEGER NOT NULL,\n    \"articleId\" INTEGER NOT NULL,\n\n    PRIMARY KEY (\"userId\", \"articleId\"),\n    CONSTRAINT \"Favorite_userId_fkey\" FOREIGN KEY (\"userId\") REFERENCES \"User\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE,\n    CONSTRAINT \"Favorite_articleId_fkey\" FOREIGN KEY (\"articleId\") REFERENCES \"Article\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE\n)",
  "CREATE TABLE IF NOT EXISTS \"Follow\" (\n    \"followerId\" INTEGER NOT NULL,\n    \"followeeId\" INTEGER NOT NULL,\n\n    PRIMARY KEY (\"followerId\", \"followeeId\"),\n    CONSTRAINT \"Follow_followerId_fkey\" FOREIGN KEY (\"followerId\") REFERENCES \"User\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE,\n    CONSTRAINT \"Follow_followeeId_fkey\" FOREIGN KEY (\"followeeId\") REFERENCES \"User\" (\"id\") ON DELETE CASCADE ON UPDATE CASCADE\n)",
  "CREATE UNIQUE INDEX IF NOT EXISTS \"User_email_key\" ON \"User\"(\"email\")",
  "CREATE UNIQUE INDEX IF NOT EXISTS \"User_username_key\" ON \"User\"(\"username\")",
  "CREATE UNIQUE INDEX IF NOT EXISTS \"Article_slug_key\" ON \"Article\"(\"slug\")",
  "CREATE INDEX IF NOT EXISTS \"Article_authorId_idx\" ON \"Article\"(\"authorId\")",
  "CREATE INDEX IF NOT EXISTS \"Article_createdAt_idx\" ON \"Article\"(\"createdAt\")",
  "CREATE UNIQUE INDEX IF NOT EXISTS \"Tag_name_key\" ON \"Tag\"(\"name\")",
  "CREATE INDEX IF NOT EXISTS \"Comment_articleId_idx\" ON \"Comment\"(\"articleId\")",
  "CREATE INDEX IF NOT EXISTS \"Favorite_articleId_idx\" ON \"Favorite\"(\"articleId\")",
  "CREATE INDEX IF NOT EXISTS \"Follow_followeeId_idx\" ON \"Follow\"(\"followeeId\")"
];
