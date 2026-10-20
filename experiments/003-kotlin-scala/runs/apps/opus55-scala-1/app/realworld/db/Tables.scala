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
    articleId: Long,
    authorId: Long,
    body: String,
    createdAt: Long,
    updatedAt: Long
)

/** Slick table mappings. The DDL itself lives in [[Schema]]. */
object Tables {

  final class Users(tag: Tag) extends Table[UserRow](tag, "users") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def username = column[String]("username")
    def email = column[String]("email")
    def passwordHash = column[String]("password_hash")
    def bio = column[Option[String]]("bio")
    def image = column[Option[String]]("image")
    def * = (id, username, email, passwordHash, bio, image).mapTo[UserRow]
  }
  val users = TableQuery[Users]

  final class Follows(tag: Tag) extends Table[(Long, Long)](tag, "follows") {
    def followerId = column[Long]("follower_id")
    def followeeId = column[Long]("followee_id")
    def * = (followerId, followeeId)
  }
  val follows = TableQuery[Follows]

  final class Articles(tag: Tag) extends Table[ArticleRow](tag, "articles") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def slug = column[String]("slug")
    def title = column[String]("title")
    def description = column[String]("description")
    def body = column[String]("body")
    def authorId = column[Long]("author_id")
    def createdAt = column[Long]("created_at")
    def updatedAt = column[Long]("updated_at")
    def * = (id, slug, title, description, body, authorId, createdAt, updatedAt).mapTo[ArticleRow]
  }
  val articles = TableQuery[Articles]

  final class Tags(tag: Tag) extends Table[(Long, String)](tag, "tags") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def * = (id, name)
  }
  val tags = TableQuery[Tags]

  final class ArticleTags(tag: Tag) extends Table[(Long, Long, Int)](tag, "article_tags") {
    def articleId = column[Long]("article_id")
    def tagId = column[Long]("tag_id")
    def position = column[Int]("position")
    def * = (articleId, tagId, position)
  }
  val articleTags = TableQuery[ArticleTags]

  final class Favorites(tag: Tag) extends Table[(Long, Long)](tag, "favorites") {
    def userId = column[Long]("user_id")
    def articleId = column[Long]("article_id")
    def * = (userId, articleId)
  }
  val favorites = TableQuery[Favorites]

  final class Comments(tag: Tag) extends Table[CommentRow](tag, "comments") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def articleId = column[Long]("article_id")
    def authorId = column[Long]("author_id")
    def body = column[String]("body")
    def createdAt = column[Long]("created_at")
    def updatedAt = column[Long]("updated_at")
    def * = (id, articleId, authorId, body, createdAt, updatedAt).mapTo[CommentRow]
  }
  val comments = TableQuery[Comments]
}
