package common

import play.api.http.HttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.mvc.Results.*

import scala.concurrent.Future

/** Renders every framework-level failure as a RealWorld style JSON error body. */
class JsonErrorHandler extends HttpErrorHandler:
  private def body(field: String, message: String) = Json.obj("errors" -> Json.obj(field -> Json.arr(message)))

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    val result = statusCode match
      case 404 => NotFound(body("route", "not found"))
      case 400 => Status(422)(body("body", "is invalid"))
      case other => Status(other)(body("request", if message.isEmpty then "error" else message))
    Future.successful(result)

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    play.api.Logger("application").error(s"Unhandled error on ${request.method} ${request.uri}", exception)
    Future.successful(InternalServerError(body("server", "internal error")))
