package services

import play.api.libs.json.*

case class ProfileView(username: String, bio: Option[String], image: Option[String], following: Boolean)
case class ArticleView(row: ArticleRow, tags: Seq[String], favorited: Boolean, favoritesCount: Int, author: ProfileView)
case class CommentView(row: CommentRow, author: ProfileView)

object Views:
  /** Optional strings become explicit JSON nulls, as the RealWorld spec requires. */
  private def opt(value: Option[String]): JsValue = value.fold[JsValue](JsNull)(JsString(_))

  def profile(p: ProfileView): JsObject =
    Json.obj("username" -> p.username, "bio" -> opt(p.bio), "image" -> opt(p.image), "following" -> p.following)

  def user(u: UserRow, token: String): JsObject =
    Json.obj("email" -> u.email, "token" -> token, "username" -> u.username, "bio" -> opt(u.bio), "image" -> opt(u.image))

  def article(a: ArticleView, withBody: Boolean): JsObject =
    val base = Json.obj(
      "slug" -> a.row.slug,
      "title" -> a.row.title,
      "description" -> a.row.description,
      "tagList" -> a.tags,
      "createdAt" -> a.row.createdAt,
      "updatedAt" -> a.row.updatedAt,
      "favorited" -> a.favorited,
      "favoritesCount" -> a.favoritesCount,
      "author" -> profile(a.author)
    )
    if withBody then base + ("body" -> JsString(a.row.body)) else base

  def comment(c: CommentView): JsObject =
    Json.obj("id" -> c.row.id, "createdAt" -> c.row.createdAt, "updatedAt" -> c.row.updatedAt,
      "body" -> c.row.body, "author" -> profile(c.author))
