package realworld.controllers

import play.api.libs.json.*
import play.api.mvc.*
import realworld.ApiError
import realworld.auth.Jwt
import realworld.db.UserRow
import realworld.services.UserService

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Shared helpers: authentication, JSON body parsing, field extraction and error recovery. */
abstract class ApiController(cc: ControllerComponents, jwt: Jwt, userService: UserService)
    extends AbstractController(cc):

  protected given ec: ExecutionContext = cc.executionContext

  /** Accepts any content type and parses JSON manually so malformed bodies produce 422 instead of 400. */
  protected val jsonBody: BodyParser[String] = parse.tolerantText(1024 * 1024)

  protected def handle(f: => Future[Result]): Future[Result] =
    Try(f).fold(Future.failed, identity).recover { case e: ApiError => e.toResult }

  private def tokenOf(req: RequestHeader): Option[String] =
    req.headers.get(AUTHORIZATION).map(_.trim).map { h =>
      val i = h.indexOf(' ')
      val scheme = if i < 0 then h else h.substring(0, i)
      if scheme.equalsIgnoreCase("Token") || scheme.equalsIgnoreCase("Bearer") then h.substring(i + 1).trim else h
    }

  /** Resolves the user of a valid token, or fails with 401. */
  protected def requireUser(req: RequestHeader): Future[(UserRow, String)] =
    tokenOf(req) match
      case None | Some("") => Future.failed(ApiError.tokenMissing)
      case Some(token) =>
        jwt.verify(token) match
          case None => Future.failed(ApiError.tokenInvalid)
          case Some(id) =>
            userService.findById(id).flatMap {
              case Some(u) => Future.successful((u, token))
              case None    => Future.failed(ApiError.tokenInvalid)
            }

  /** Resolves the user if a token is present; a present but invalid token is rejected with 401. */
  protected def optionalUser(req: RequestHeader): Future[Option[UserRow]] =
    tokenOf(req) match
      case None | Some("") => Future.successful(None)
      case Some(_)         => requireUser(req).map(r => Some(r._1))

  /** Parses the body and returns the object nested under `root` (e.g. "user", "article"). */
  protected def rootObject(raw: String, root: String): JsObject =
    Try(Json.parse(raw)).toOption.flatMap(js => (js \ root).asOpt[JsObject]).getOrElse(throw ApiError.invalidBody)

  /** Field lookup that distinguishes an absent key (None) from an explicit null (Some(JsNull)). */
  protected def field(obj: JsObject, name: String): Option[JsValue] = obj.value.get(name)

  protected def stringField(obj: JsObject, name: String): Option[String] =
    field(obj, name).collect { case JsString(s) => s }

object ApiController:
  private val timestampFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def timestamp(epochMillis: Long): String = timestampFormat.format(Instant.ofEpochMilli(epochMillis))

  def validatePassword(password: Option[String]): Option[(String, String)] = password match
    case None | Some("")       => Some("password" -> "can't be blank")
    case Some(p) if p.length < 8 => Some("password" -> "is too short (minimum is 8 characters)")
    case _                     => None
