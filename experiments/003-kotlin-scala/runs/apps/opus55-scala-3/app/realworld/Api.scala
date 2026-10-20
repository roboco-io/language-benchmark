package realworld

import play.api.libs.json.*
import play.api.mvc.*

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import scala.concurrent.{ExecutionContext, Future}

/** JSON rendering for API responses. */
object Render:
  private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  def time(millis: Long): String = iso.format(Instant.ofEpochMilli(millis))

  private def opt(s: Option[String]): JsValue = s.fold(JsNull)(JsString(_))

  def user(u: User, token: String): JsObject = Json.obj("user" -> Json.obj(
    "username" -> u.username, "email" -> u.email, "token" -> token, "bio" -> opt(u.bio), "image" -> opt(u.image)))

  def profile(p: Profile): JsObject = Json.obj(
    "username" -> p.username, "bio" -> opt(p.bio), "image" -> opt(p.image), "following" -> p.following)

  def article(a: ArticleView, withBody: Boolean = true): JsObject =
    val base = Json.obj(
      "slug" -> a.row.slug,
      "title" -> a.row.title,
      "description" -> a.row.description,
      "tagList" -> a.tags,
      "createdAt" -> time(a.row.createdAt),
      "updatedAt" -> time(a.row.updatedAt),
      "favorited" -> a.favorited,
      "favoritesCount" -> a.favoritesCount,
      "author" -> profile(a.author))
    if withBody then base + ("body" -> JsString(a.row.body)) else base

  def articles(page: (Seq[ArticleView], Int)): JsObject =
    Json.obj("articles" -> page._1.map(article(_, withBody = false)), "articlesCount" -> page._2)

  def comment(c: CommentView): JsObject = Json.obj(
    "id" -> c.id, "createdAt" -> time(c.createdAt), "updatedAt" -> time(c.updatedAt),
    "body" -> c.body, "author" -> profile(c.author))

/** Shared helpers for controllers: error responses, authentication, request parsing. */
abstract class ApiController(cc: ControllerComponents, jwt: Jwt)(using val ec: ExecutionContext) extends AbstractController(cc):

  protected def errors(status: Status, fields: (String, String)*): Result =
    status(Json.obj("errors" -> JsObject(fields.groupMap(_._1)(_._2).map((k, v) => k -> Json.toJson(v)))))

  protected def unauthorized(reason: String): Result = errors(Unauthorized, "token" -> reason)
  protected def notFound(what: String): Result = errors(NotFound, what -> "not found")
  protected def forbidden(what: String): Result = errors(Forbidden, what -> "forbidden")
  protected def blank(fields: String*): Result = errors(UnprocessableEntity, fields.map(_ -> "can't be blank")*)

  private def token(req: RequestHeader): Option[String] =
    req.headers.get("Authorization").map(_.trim).map { h =>
      val i = h.indexOf(' ')
      if i < 0 then h else h.substring(i + 1).trim
    }

  /** Left(401) when the token is missing or invalid, otherwise the user id. */
  protected def requireUser(req: RequestHeader): Either[Result, Long] =
    token(req) match
      case None => Left(unauthorized("is missing"))
      case Some(t) => jwt.verify(t).toRight(unauthorized("is invalid"))

  /** Anonymous access is allowed; a present but invalid token is rejected. */
  protected def optionalUser(req: RequestHeader): Either[Result, Option[Long]] =
    token(req) match
      case None => Right(None)
      case Some(t) => jwt.verify(t).map(Some(_)).toRight(unauthorized("is invalid"))

  protected def authed(f: (Request[AnyContent], Long) => Future[Result]): Action[AnyContent] =
    Action.async(req => requireUser(req).fold(Future.successful, f(req, _)))

  protected def maybeAuthed(f: (Request[AnyContent], Option[Long]) => Future[Result]): Action[AnyContent] =
    Action.async(req => optionalUser(req).fold(Future.successful, f(req, _)))

  /** The object under `root` in the JSON body, or a 422 response. */
  protected def bodyObject(req: Request[AnyContent], root: String): Either[Result, JsObject] =
    req.body.asJson.flatMap(js => (js \ root).asOpt[JsObject])
      .toRight(errors(UnprocessableEntity, "body" -> "is invalid"))

  /** A required, non-blank string field. */
  protected def str(o: JsObject, field: String): Option[String] =
    (o \ field).asOpt[String].filter(_.trim.nonEmpty)

  protected def ok(js: JsValue): Future[Result] = Future.successful(Ok(js))
  protected def done(r: Result): Future[Result] = Future.successful(r)
