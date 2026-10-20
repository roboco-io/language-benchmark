package controllers

import models.*
import models.Tables.*
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.*
import play.api.mvc.*
import services.Security
import slick.jdbc.JdbcProfile
import slick.jdbc.SQLiteProfile.api.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

/** Shared helpers: authentication, error rendering and JSON views. */
abstract class ApiSupport(cc: ControllerComponents, dbp: DatabaseConfigProvider, security: Security)(using protected val ec: ExecutionContext)
    extends AbstractController(cc) {

  protected val db = dbp.get[JdbcProfile].db

  private val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  protected def now(): String = ts.format(Instant.now())

  // ---- errors -----------------------------------------------------------
  protected def errors(status: Int, entries: (String, String)*): Result =
    Status(status)(Json.obj("errors" -> JsObject(entries.groupMap(_._1)(_._2).map((k, v) => k -> JsArray(v.map(JsString(_)))).toSeq)))

  protected def unauthorized(msg: String = "is missing"): Result = errors(401, "token" -> msg)
  protected def invalid(entries: (String, String)*): Result = errors(422, entries*)
  protected def notFound(what: String): Result = errors(404, what -> "not found")
  protected def forbidden(what: String): Result = errors(403, what -> "forbidden")

  // ---- authentication ---------------------------------------------------
  protected def bearer(request: RequestHeader): Option[String] =
    request.headers.get("Authorization").flatMap { h =>
      val parts = h.trim.split("\\s+", 2)
      if (parts.length == 2 && (parts(0).equalsIgnoreCase("Token") || parts(0).equalsIgnoreCase("Bearer"))) Some(parts(1)) else None
    }

  protected def withAuth(request: RequestHeader)(f: (UserRow, String) => Future[Result]): Future[Result] =
    bearer(request) match {
      case None => Future.successful(unauthorized())
      case Some(token) =>
        security.verifyToken(token) match {
          case None => Future.successful(unauthorized("is invalid"))
          case Some(uid) =>
            db.run(users.filter(_.id === uid).result.headOption).flatMap {
              case Some(u) => f(u, token)
              case None => Future.successful(unauthorized("is invalid"))
            }
        }
    }

  /** Authentication is optional: a missing or invalid token yields an anonymous request. */
  protected def withOptAuth(request: RequestHeader)(f: Option[UserRow] => Future[Result]): Future[Result] =
    bearer(request).flatMap(security.verifyToken) match {
      case None => f(None)
      case Some(uid) => db.run(users.filter(_.id === uid).result.headOption).flatMap(f)
    }

  // ---- request body helpers ----------------------------------------------
  /** Field state in a JSON object: missing, explicit null, or a value. */
  protected def field(obj: JsValue, name: String): JsLookupResult = obj \ name

  protected def strField(obj: JsValue, name: String): Option[String] = (obj \ name).asOpt[String]

  protected def blank(s: Option[String]): Boolean = s.forall(_.trim.isEmpty)

  // ---- views ------------------------------------------------------------
  protected def userJson(u: UserRow, token: String): JsObject = Json.obj(
    "user" -> Json.obj(
      "email" -> u.email, "token" -> token, "username" -> u.username,
      "bio" -> u.bio, "image" -> u.image
    )
  )

  protected def profileObj(u: UserRow, following: Boolean): JsObject =
    Json.obj("username" -> u.username, "bio" -> u.bio, "image" -> u.image, "following" -> following)

  protected def isFollowing(me: Option[UserRow], target: Long): DBIO[Boolean] = me match {
    case Some(m) => follows.filter(f => f.followerId === m.id && f.followeeId === target).exists.result
    case None => DBIO.successful(false)
  }

  /** Builds article views for a batch of rows with a constant number of queries. */
  protected def articleViews(rows: Seq[ArticleRow], me: Option[UserRow], withBody: Boolean): DBIO[Seq[JsObject]] =
    if (rows.isEmpty) DBIO.successful(Seq.empty)
    else {
      val ids = rows.map(_.id).toSet
      val authorIds = rows.map(_.authorId).toSet
      for {
        authors <- users.filter(_.id.inSet(authorIds)).result.map(_.map(u => u.id -> u).toMap)
        tags <- articleTags.filter(_.articleId.inSet(ids)).sortBy(_.id).result
          .map(_.groupMap(_._2)(_._3))
        counts <- favorites.filter(_.articleId.inSet(ids)).groupBy(_.articleId).map((a, g) => (a, g.length)).result
          .map(_.toMap)
        mine <- me match {
          case Some(m) => favorites.filter(f => f.userId === m.id && f.articleId.inSet(ids)).map(_.articleId).result.map(_.toSet)
          case None => DBIO.successful(Set.empty[Long])
        }
        followed <- me match {
          case Some(m) => follows.filter(f => f.followerId === m.id && f.followeeId.inSet(authorIds)).map(_.followeeId).result.map(_.toSet)
          case None => DBIO.successful(Set.empty[Long])
        }
      } yield rows.map { a =>
        val base = Json.obj(
          "slug" -> a.slug, "title" -> a.title, "description" -> a.description,
          "tagList" -> tags.getOrElse(a.id, Seq.empty).sorted,
          "createdAt" -> a.createdAt, "updatedAt" -> a.updatedAt,
          "favorited" -> mine.contains(a.id), "favoritesCount" -> counts.getOrElse(a.id, 0),
          "author" -> profileObj(authors(a.authorId), followed.contains(a.authorId))
        )
        if (withBody) base + ("body" -> JsString(a.body)) else base
      }
    }

  protected def commentView(c: CommentRow, author: UserRow, following: Boolean): JsObject = Json.obj(
    "id" -> c.id, "createdAt" -> c.createdAt, "updatedAt" -> c.updatedAt, "body" -> c.body,
    "author" -> profileObj(author, following)
  )
}
