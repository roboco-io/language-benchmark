package realworld.db

import slick.jdbc.SQLiteProfile.api.*

final case class UserRow(
    id: Long,
    username: String,
    email: String,
    passwordHash: String,
    bio: Option[String],
    image: Option[String]
)

final case class ArticleRow(
    id: Long,
    slug: String,
    title: String,
    description: String,
    body: String,
    authorId: Long,
    createdAt: Long,
    updatedAt: Long
)

final case class CommentRow(
    id: Long,
    body: String,
    articleId: Long,
    authorId: Long,
    createdAt: Long,
    updatedAt: Long
)

class Users(tag: Tag) extends Table[UserRow](tag, "users"):
  def id           = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def username     = column[String]("username")
  def email        = column[String]("email")
  def passwordHash = column[String]("password_hash")
  def bio          = column[Option[String]]("bio")
  def image        = column[Option[String]]("image")
  def *            = (id, username, email, passwordHash, bio, image).mapTo[UserRow]

class Follows(tag: Tag) extends Table[(Long, Long)](tag, "follows"):
  def followerId = column[Long]("follower_id")
  def followeeId = column[Long]("followee_id")
  def *          = (followerId, followeeId)

class Articles(tag: Tag) extends Table[ArticleRow](tag, "articles"):
  def id          = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def slug        = column[String]("slug")
  def title       = column[String]("title")
  def description = column[String]("description")
  def body        = column[String]("body")
  def authorId    = column[Long]("author_id")
  def createdAt   = column[Long]("created_at")
  def updatedAt   = column[Long]("updated_at")
  def * = (id, slug, title, description, body, authorId, createdAt, updatedAt).mapTo[ArticleRow]

class Tags(tag: Tag) extends Table[(Long, String)](tag, "tags"):
  def id   = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def *    = (id, name)

/** Link between articles and tags; `position` keeps the order of the submitted tagList. */
class ArticleTags(tag: Tag) extends Table[(Long, Long, Int)](tag, "article_tags"):
  def articleId = column[Long]("article_id")
  def tagId     = column[Long]("tag_id")
  def position  = column[Int]("position")
  def *         = (articleId, tagId, position)

class Favorites(tag: Tag) extends Table[(Long, Long)](tag, "favorites"):
  def userId    = column[Long]("user_id")
  def articleId = column[Long]("article_id")
  def *         = (userId, articleId)

class Comments(tag: Tag) extends Table[CommentRow](tag, "comments"):
  def id        = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def body      = column[String]("body")
  def articleId = column[Long]("article_id")
  def authorId  = column[Long]("author_id")
  def createdAt = column[Long]("created_at")
  def updatedAt = column[Long]("updated_at")
  def *         = (id, body, articleId, authorId, createdAt, updatedAt).mapTo[CommentRow]

object Tables:
  val users       = TableQuery[Users]
  val follows     = TableQuery[Follows]
  val articles    = TableQuery[Articles]
  val tags        = TableQuery[Tags]
  val articleTags = TableQuery[ArticleTags]
  val favorites   = TableQuery[Favorites]
  val comments    = TableQuery[Comments]

  /** Idempotent DDL, executed on every startup so an empty database gets its schema. */
  val schemaStatements: Seq[String] = Seq(
    """CREATE TABLE IF NOT EXISTS users (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  username TEXT NOT NULL UNIQUE,
      |  email TEXT NOT NULL UNIQUE,
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
    "CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id)",
    "CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at DESC, id DESC)",
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
      |  body TEXT NOT NULL,
      |  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      |  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  created_at INTEGER NOT NULL,
      |  updated_at INTEGER NOT NULL
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)"
  )
