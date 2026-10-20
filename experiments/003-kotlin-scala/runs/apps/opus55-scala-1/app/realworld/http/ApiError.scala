package realworld.http

import play.api.libs.json.Json
import play.api.mvc.{Result, Results}

import scala.collection.mutable

/** An error that maps directly to a RealWorld error response: `{"errors": {"field": ["message"]}}`. */
final case class ApiError(status: Int, errors: Seq[(String, Seq[String])])
    extends RuntimeException(errors.map((k, v) => s"$k ${v.mkString(", ")}").mkString("; "), null, false, false) {
  def toResult: Result = Results.Status(status)(ApiError.body(errors))
}

object ApiError {
  def body(errors: Seq[(String, Seq[String])]) =
    Json.obj("errors" -> Json.toJsObject(errors.map((k, v) => k -> v).toMap))

  private def single(status: Int, field: String, message: String) = ApiError(status, Seq(field -> Seq(message)))

  def validation(field: String, message: String): ApiError = single(422, field, message)
  def notFound(field: String): ApiError = single(404, field, "not found")
  def forbidden(field: String): ApiError = single(403, field, "forbidden")
  def unauthorized(field: String, message: String): ApiError = single(401, field, message)
  def conflict(field: String): ApiError = single(409, field, "has already been taken")

  val tokenMissing: ApiError = unauthorized("token", "is missing")
  val tokenInvalid: ApiError = unauthorized("token", "is invalid")
  val invalidCredentials: ApiError = unauthorized("credentials", "invalid")
}

/** Accumulates field errors so that a request reports every problem at once. */
final class Errors {
  private val buf = mutable.LinkedHashMap.empty[String, Vector[String]]

  def add(field: String, message: String): Unit =
    buf.update(field, buf.getOrElse(field, Vector.empty) :+ message)

  def isEmpty: Boolean = buf.isEmpty

  def throwIfAny(status: Int = 422): Unit =
    if (buf.nonEmpty) throw ApiError(status, buf.toSeq)
}
