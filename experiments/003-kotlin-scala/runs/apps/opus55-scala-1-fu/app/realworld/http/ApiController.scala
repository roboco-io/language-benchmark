package realworld.http

import org.apache.pekko.util.ByteString
import play.api.mvc.*
import realworld.auth.Jwt
import realworld.db.UserRow
import realworld.services.UserService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** The authenticated caller and the token they presented. */
final case class Auth(user: UserRow, token: String)

/** Resolves `Authorization: Token <jwt>` (or `Bearer <jwt>`) headers to users. */
final class Authenticator(jwt: Jwt, userService: UserService)(using ec: ExecutionContext) {

  private def extract(header: String): Option[String] =
    header.trim.split("\\s+", 2) match {
      case Array(scheme, token) if scheme.equalsIgnoreCase("Token") || scheme.equalsIgnoreCase("Bearer") =>
        Some(token.trim).filter(_.nonEmpty)
      case _ => None
    }

  /** None when no Authorization header was sent; fails with 401 when the token is invalid. */
  def optional(req: RequestHeader): Future[Option[Auth]] =
    req.headers.get("Authorization") match {
      case None => Future.successful(None)
      case Some(h) =>
        extract(h).flatMap(t => jwt.verify(t).map(_ -> t)) match {
          case None => Future.failed(ApiError.tokenInvalid)
          case Some((id, token)) =>
            userService.findById(id).map {
              case Some(u) => Some(Auth(u, token))
              case None    => throw ApiError.tokenInvalid
            }
        }
    }

  def required(req: RequestHeader): Future[Auth] =
    optional(req).map(_.getOrElse(throw ApiError.tokenMissing))
}

/** Base controller: JSON bodies are read as raw bytes and parsed by hand so that malformed input yields 422. */
abstract class ApiController(cc: ControllerComponents, auth: Authenticator) extends AbstractController(cc) {
  protected given ec: ExecutionContext = cc.executionContext

  private def handle(f: => Future[Result]): Future[Result] =
    Future.fromTry(Try(f)).flatten.recover { case e: ApiError => e.toResult }

  protected def open(block: (Request[ByteString], Option[Auth]) => Future[Result]): Action[ByteString] =
    Action.async(parse.byteString) { req => handle(auth.optional(req).flatMap(a => block(req, a))) }

  protected def secured(block: (Request[ByteString], Auth) => Future[Result]): Action[ByteString] =
    Action.async(parse.byteString) { req => handle(auth.required(req).flatMap(a => block(req, a))) }

  protected def intParam(req: RequestHeader, name: String, default: Int, min: Int, max: Int): Int =
    req.getQueryString(name).flatMap(_.trim.toIntOption).fold(default)(v => v.max(min).min(max))
}
