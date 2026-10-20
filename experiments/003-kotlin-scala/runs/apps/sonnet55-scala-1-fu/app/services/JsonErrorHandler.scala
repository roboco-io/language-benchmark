package services

import play.api.http.HttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.mvc.Results.*

import scala.concurrent.Future

/** Renders all framework-level errors in the RealWorld `{"errors": {...}}` shape. */
class JsonErrorHandler extends HttpErrorHandler {
  private def body(key: String, msg: String) = Json.obj("errors" -> Json.obj(key -> Json.arr(msg)))

  def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    Future.successful(statusCode match {
      case 404 => NotFound(body("resource", "not found"))
      case 400 => BadRequest(body("body", "is invalid"))
      case s => Status(s)(body("request", if (message.isEmpty) "error" else message))
    })

  def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    Future.successful(InternalServerError(body("server", "internal error")))
}
