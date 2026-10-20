package realworld.http

import org.slf4j.LoggerFactory
import play.api.http.HttpErrorHandler
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.Future

/** Renders every framework-level error as a RealWorld-style JSON error body. */
final class ErrorHandler extends HttpErrorHandler {
  private val log = LoggerFactory.getLogger(getClass)

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {
    val (field, text) = statusCode match {
      case 404 => "route" -> "not found"
      case 413 => "body" -> "is too large"
      case _   => "request" -> (if (message.nonEmpty) message else "is invalid")
    }
    Future.successful(Results.Status(statusCode)(ApiError.body(Seq(field -> Seq(text)))))
  }

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    exception match {
      case e: ApiError => Future.successful(e.toResult)
      case e =>
        log.error(s"Unhandled error on ${request.method} ${request.uri}", e)
        Future.successful(Results.InternalServerError(ApiError.body(Seq("server" -> Seq("internal error")))))
    }
}
