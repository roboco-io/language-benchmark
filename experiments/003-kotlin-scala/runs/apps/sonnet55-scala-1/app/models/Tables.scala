package models

import slick.jdbc.SQLiteProfile.api.*

case class UserRow(id: Long, email: String, username: String, passwordHash: String, bio: Option[String], image: Option[String])
case class ArticleRow(id: Long, slug: String, title: String, description: String, body: String, authorId: Long, createdAt: String, updatedAt: String)
case class CommentRow(id: Long, articleId: Long, authorId: Long, body: String, createdAt: String, updatedAt: String)

class Users(tag: Tag) extends Table[UserRow](tag, "users") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def email = column[String]("email", O.Unique)
  def username = column[String]("username", O.Unique)
  def passwordHash = column[String]("password_hash")
  def bio = column[Option[String]]("bio")
  def image = column[Option[String]]("image")
  def * = (id, email, username, passwordHash, bio, image).mapTo[UserRow]
}

class Follows(tag: Tag) extends Table[(Long, Long)](tag, "follows") {
  def followerId = column[Long]("follower_id")
  def followeeId = column[Long]("followee_id")
  def pk = primaryKey("follows_pk", (followerId, followeeId))
  def * = (followerId, followeeId)
}

class Articles(tag: Tag) extends Table[ArticleRow](tag, "articles") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def slug = column[String]("slug", O.Unique)
  def title = column[String]("title")
  def description = column[String]("description")
  def body = column[String]("body")
  def authorId = column[Long]("author_id")
  def createdAt = column[String]("created_at")
  def updatedAt = column[String]("updated_at")
  def * = (id, slug, title, description, body, authorId, createdAt, updatedAt).mapTo[ArticleRow]
}

class ArticleTags(tag: Tag) extends Table[(Long, Long, String)](tag, "article_tags") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def articleId = column[Long]("article_id")
  def name = column[String]("name")
  def * = (id, articleId, name)
}

class Favorites(tag: Tag) extends Table[(Long, Long)](tag, "favorites") {
  def userId = column[Long]("user_id")
  def articleId = column[Long]("article_id")
  def pk = primaryKey("favorites_pk", (userId, articleId))
  def * = (userId, articleId)
}

class Comments(tag: Tag) extends Table[CommentRow](tag, "comments") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def articleId = column[Long]("article_id")
  def authorId = column[Long]("author_id")
  def body = column[String]("body")
  def createdAt = column[String]("created_at")
  def updatedAt = column[String]("updated_at")
  def * = (id, articleId, authorId, body, createdAt, updatedAt).mapTo[CommentRow]
}

object Tables {
  val users = TableQuery[Users]
  val follows = TableQuery[Follows]
  val articles = TableQuery[Articles]
  val articleTags = TableQuery[ArticleTags]
  val favorites = TableQuery[Favorites]
  val comments = TableQuery[Comments]
}
