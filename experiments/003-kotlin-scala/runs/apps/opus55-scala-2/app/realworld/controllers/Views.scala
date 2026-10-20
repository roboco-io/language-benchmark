package realworld.controllers

import play.api.libs.json.*
import realworld.controllers.ApiController.timestamp
import realworld.db.UserRow
import realworld.services.{ArticleView, CommentView, Profile}

/** JSON renderings of the RealWorld response objects. */
object Views:
  def user(u: UserRow, token: String): JsObject =
    Json.obj(
      "user" -> Json.obj(
        "email"    -> u.email,
        "token"    -> token,
        "username" -> u.username,
        "bio"      -> u.bio,
        "image"    -> u.image
      )
    )

  def profileJson(p: Profile): JsObject =
    Json.obj(
      "username"  -> p.user.username,
      "bio"       -> p.user.bio,
      "image"     -> p.user.image,
      "following" -> p.following
    )

  def profile(p: Profile): JsObject = Json.obj("profile" -> profileJson(p))

  def articleJson(a: ArticleView, withBody: Boolean): JsObject =
    val base = Json.obj(
      "slug"        -> a.article.slug,
      "title"       -> a.article.title,
      "description" -> a.article.description
    )
    val body = if withBody then Json.obj("body" -> a.article.body) else Json.obj()
    base ++ body ++ Json.obj(
      "tagList"        -> a.tags,
      "createdAt"      -> timestamp(a.article.createdAt),
      "updatedAt"      -> timestamp(a.article.updatedAt),
      "favorited"      -> a.favorited,
      "favoritesCount" -> a.favoritesCount,
      "author"         -> profileJson(a.author)
    )

  def article(a: ArticleView): JsObject = Json.obj("article" -> articleJson(a, withBody = true))

  def articles(list: Seq[ArticleView], count: Int): JsObject =
    Json.obj("articles" -> list.map(articleJson(_, withBody = false)), "articlesCount" -> count)

  def commentJson(c: CommentView): JsObject =
    Json.obj(
      "id"        -> c.comment.id,
      "createdAt" -> timestamp(c.comment.createdAt),
      "updatedAt" -> timestamp(c.comment.updatedAt),
      "body"      -> c.comment.body,
      "author"    -> profileJson(c.author)
    )

  def comment(c: CommentView): JsObject = Json.obj("comment" -> commentJson(c))

  def comments(list: Seq[CommentView]): JsObject = Json.obj("comments" -> list.map(commentJson))
