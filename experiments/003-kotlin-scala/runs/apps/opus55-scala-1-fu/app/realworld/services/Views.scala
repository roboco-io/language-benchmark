package realworld.services

import play.api.libs.json.*
import realworld.db.UserRow

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

final case class ProfileView(username: String, bio: Option[String], image: Option[String], following: Boolean)

final case class ArticleView(
    slug: String,
    title: String,
    description: String,
    body: String,
    tagList: Seq[String],
    createdAt: Long,
    updatedAt: Long,
    favorited: Boolean,
    favoritesCount: Int,
    author: ProfileView
)

final case class CommentView(id: Long, body: String, createdAt: Long, updatedAt: Long, author: ProfileView)

/** JSON shapes of the RealWorld API responses. */
object Views {
  private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def time(millis: Long): JsString = JsString(timestamp.format(Instant.ofEpochMilli(millis)))

  private def nullable(v: Option[String]): JsValue = v.fold[JsValue](JsNull)(JsString(_))

  def user(u: UserRow, token: String): JsObject =
    Json.obj(
      "user" -> Json.obj(
        "username" -> u.username,
        "email" -> u.email,
        "bio" -> nullable(u.bio),
        "image" -> nullable(u.image),
        "token" -> token
      )
    )

  def profileJson(p: ProfileView): JsObject =
    Json.obj("username" -> p.username, "bio" -> nullable(p.bio), "image" -> nullable(p.image), "following" -> p.following)

  def profile(p: ProfileView): JsObject = Json.obj("profile" -> profileJson(p))

  def articleJson(a: ArticleView, includeBody: Boolean): JsObject = {
    val base = Json.obj(
      "slug" -> a.slug,
      "title" -> a.title,
      "description" -> a.description
    )
    val withBody = if (includeBody) base + ("body" -> JsString(a.body)) else base
    withBody ++ Json.obj(
      "tagList" -> a.tagList,
      "createdAt" -> time(a.createdAt),
      "updatedAt" -> time(a.updatedAt),
      "favorited" -> a.favorited,
      "favoritesCount" -> a.favoritesCount,
      "author" -> profileJson(a.author)
    )
  }

  def article(a: ArticleView): JsObject = Json.obj("article" -> articleJson(a, includeBody = true))

  def articles(list: Seq[ArticleView], count: Int): JsObject =
    Json.obj("articles" -> list.map(articleJson(_, includeBody = false)), "articlesCount" -> count)

  def commentJson(c: CommentView): JsObject =
    Json.obj(
      "id" -> c.id,
      "createdAt" -> time(c.createdAt),
      "updatedAt" -> time(c.updatedAt),
      "body" -> c.body,
      "author" -> profileJson(c.author)
    )

  def comment(c: CommentView): JsObject = Json.obj("comment" -> commentJson(c))

  def comments(list: Seq[CommentView]): JsObject = Json.obj("comments" -> list.map(commentJson))

  def tags(list: Seq[String]): JsObject = Json.obj("tags" -> list)
}
