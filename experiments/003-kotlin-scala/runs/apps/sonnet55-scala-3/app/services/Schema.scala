package services

import slick.jdbc.SQLiteProfile.api.*

case class UserRow(id: Long, username: String, email: String, passwordHash: String, bio: Option[String], image: Option[String])
case class ArticleRow(id: Long, slug: String, title: String, description: String, body: String, authorId: Long, createdAt: String, updatedAt: String)
case class CommentRow(id: Long, articleId: Long, authorId: Long, body: String, createdAt: String, updatedAt: String)

class Users(tag: Tag) extends Table[UserRow](tag, "users"):
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def username = column[String]("username")
  def email = column[String]("email")
  def passwordHash = column[String]("password_hash")
  def bio = column[Option[String]]("bio")
  def image = column[Option[String]]("image")
  def * = (id, username, email, passwordHash, bio, image).mapTo[UserRow]

class Articles(tag: Tag) extends Table[ArticleRow](tag, "articles"):
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def slug = column[String]("slug")
  def title = column[String]("title")
  def description = column[String]("description")
  def body = column[String]("body")
  def authorId = column[Long]("author_id")
  def createdAt = column[String]("created_at")
  def updatedAt = column[String]("updated_at")
  def * = (id, slug, title, description, body, authorId, createdAt, updatedAt).mapTo[ArticleRow]

class ArticleTags(tag: Tag) extends Table[(Long, String)](tag, "article_tags"):
  def articleId = column[Long]("article_id")
  def name = column[String]("tag")
  def * = (articleId, name)

class Favorites(tag: Tag) extends Table[(Long, Long)](tag, "favorites"):
  def userId = column[Long]("user_id")
  def articleId = column[Long]("article_id")
  def * = (userId, articleId)

class Follows(tag: Tag) extends Table[(Long, Long)](tag, "follows"):
  def followerId = column[Long]("follower_id")
  def followedId = column[Long]("followed_id")
  def * = (followerId, followedId)

class Comments(tag: Tag) extends Table[CommentRow](tag, "comments"):
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def articleId = column[Long]("article_id")
  def authorId = column[Long]("author_id")
  def body = column[String]("body")
  def createdAt = column[String]("created_at")
  def updatedAt = column[String]("updated_at")
  def * = (id, articleId, authorId, body, createdAt, updatedAt).mapTo[CommentRow]

object Schema:
  val users = TableQuery[Users]
  val articles = TableQuery[Articles]
  val articleTags = TableQuery[ArticleTags]
  val favorites = TableQuery[Favorites]
  val follows = TableQuery[Follows]
  val comments = TableQuery[Comments]

  /** Idempotent DDL: creates every table and index only when it is missing (empty database start). */
  val create: DBIO[Unit] = DBIO.seq(
    sqlu"""CREATE TABLE IF NOT EXISTS users (
             id INTEGER PRIMARY KEY AUTOINCREMENT,
             username TEXT NOT NULL UNIQUE,
             email TEXT NOT NULL UNIQUE,
             password_hash TEXT NOT NULL,
             bio TEXT,
             image TEXT)""",
    sqlu"""CREATE TABLE IF NOT EXISTS articles (
             id INTEGER PRIMARY KEY AUTOINCREMENT,
             slug TEXT NOT NULL UNIQUE,
             title TEXT NOT NULL,
             description TEXT NOT NULL,
             body TEXT NOT NULL,
             author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
             created_at TEXT NOT NULL,
             updated_at TEXT NOT NULL)""",
    sqlu"""CREATE TABLE IF NOT EXISTS article_tags (
             article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
             tag TEXT NOT NULL,
             PRIMARY KEY (article_id, tag))""",
    sqlu"""CREATE TABLE IF NOT EXISTS favorites (
             user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
             article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
             PRIMARY KEY (user_id, article_id))""",
    sqlu"""CREATE TABLE IF NOT EXISTS follows (
             follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
             followed_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
             PRIMARY KEY (follower_id, followed_id))""",
    sqlu"""CREATE TABLE IF NOT EXISTS comments (
             id INTEGER PRIMARY KEY AUTOINCREMENT,
             article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
             author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
             body TEXT NOT NULL,
             created_at TEXT NOT NULL,
             updated_at TEXT NOT NULL)""",
    sqlu"CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id, id)",
    sqlu"CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag)",
    sqlu"CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id)",
    sqlu"CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)"
  )
