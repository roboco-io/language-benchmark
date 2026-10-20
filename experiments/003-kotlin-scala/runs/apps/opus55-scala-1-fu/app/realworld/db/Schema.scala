package realworld.db

/** Idempotent DDL, executed at startup so an empty database gets its schema automatically. */
object Schema {
  val statements: Seq[String] = Seq(
    """CREATE TABLE IF NOT EXISTS users (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  username TEXT NOT NULL UNIQUE,
      |  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
      |  password_hash TEXT NOT NULL,
      |  bio TEXT,
      |  image TEXT
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS follows (
      |  follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  PRIMARY KEY (follower_id, followee_id)
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS idx_follows_followee ON follows(followee_id)",
    """CREATE TABLE IF NOT EXISTS articles (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  slug TEXT NOT NULL UNIQUE,
      |  title TEXT NOT NULL,
      |  description TEXT NOT NULL,
      |  body TEXT NOT NULL,
      |  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  created_at INTEGER NOT NULL,
      |  updated_at INTEGER NOT NULL
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id, created_at)",
    "CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at)",
    """CREATE TABLE IF NOT EXISTS tags (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  name TEXT NOT NULL UNIQUE
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS article_tags (
      |  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      |  tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      |  position INTEGER NOT NULL,
      |  PRIMARY KEY (article_id, tag_id)
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag_id)",
    """CREATE TABLE IF NOT EXISTS favorites (
      |  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      |  PRIMARY KEY (user_id, article_id)
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id)",
    """CREATE TABLE IF NOT EXISTS comments (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      |  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  body TEXT NOT NULL,
      |  created_at INTEGER NOT NULL,
      |  updated_at INTEGER NOT NULL
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)"
  )
}
