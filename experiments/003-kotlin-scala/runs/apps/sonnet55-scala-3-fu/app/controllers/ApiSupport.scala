package controllers

import play.api.libs.json.*
import play.api.mvc.*
import common.{ApiException, Jwt}

import scala.concurrent.{ExecutionContext, Future}

/** Shared helpers: error mapping, authentication and lenient request-body field extraction. */
trait ApiSupport extends BaseController:
  protected given ec: ExecutionContext = defaultExecutionContext
  protected def jwt: Jwt

  /** Runs an action body and converts [[ApiException]]s into RealWorld error responses. */
  protected def handle(block: => Future[Result]): Future[Result] =
    Future.unit.flatMap(_ => block).recover {
      case ApiException(status, errors) => Status(status)(Json.obj("errors" -> errors))
    }

  private def bearer(req: RequestHeader): Option[String] =
    req.headers.get("Authorization").flatMap { h =>
      val parts = h.trim.split("\\s+", 2)
      if parts.length == 2 && (parts(0).equalsIgnoreCase("Token") || parts(0).equalsIgnoreCase("Bearer")) then Some(parts(1)) else None
    }

  /** The user id for a valid token, `None` when the header is missing; a bad token is a 401. */
  protected def optionalUser(req: RequestHeader): Option[Long] =
    bearer(req).map(t => jwt.verify(t).getOrElse(throw ApiException(401, "token", "is invalid")))

  protected def requireUser(req: RequestHeader): Long =
    optionalUser(req).getOrElse(throw ApiException(401, "token", "is missing"))

  /** The wrapper object (`{"user": {...}}`) of a request body. */
  protected def wrapper(req: Request[JsValue], key: String): JsObject =
    (req.body \ key).toOption match
      case Some(o: JsObject) => o
      case _ => throw ApiException(422, key, "can't be blank")

  /** Required, non-blank string: absent, null, non-string and blank values are all "can't be blank". */
  protected def required(o: JsObject, field: String): Either[(String, Seq[String]), String] =
    o.value.get(field) match
      case Some(JsString(s)) if s.trim.nonEmpty => Right(s)
      case _ => Left(field -> Seq("can't be blank"))

  /** Optional, non-blank string: absent -> None, present but null/blank/non-string -> 422. */
  protected def presentNonBlank(o: JsObject, field: String): Option[String] =
    o.value.get(field).map {
      case JsString(s) if s.trim.nonEmpty => s
      case _ => throw ApiException.blank(field)
    }

  /** Nullable string: absent -> None, null or "" -> Some(None), text -> Some(Some(text)). */
  protected def nullable(o: JsObject, field: String): Option[Option[String]] =
    o.value.get(field).map {
      case JsString(s) => Some(s).filter(_.nonEmpty)
      case JsNull => None
      case _ => throw ApiException(422, field, "is invalid")
    }

  protected def failIfAny(errors: Seq[(String, Seq[String])]): Unit =
    if errors.nonEmpty then throw ApiException(422, errors.toMap)

  protected def intParam(req: RequestHeader, name: String, default: Int): Int =
    req.getQueryString(name).flatMap(_.toIntOption).fold(default)(_.max(0))
