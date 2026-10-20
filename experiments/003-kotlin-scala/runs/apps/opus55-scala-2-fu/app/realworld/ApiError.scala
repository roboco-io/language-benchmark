package realworld

import play.api.libs.json.*
import play.api.mvc.{Result, Results}

/** An error rendered as the RealWorld error body: `{"errors": {"field": ["message"]}}`. */
final case class ApiError(status: Int, errors: Seq[(String, String)])
    extends RuntimeException(errors.map((f, m) => s"$f $m").mkString(", "), null, false, false):

  def toResult: Result = Results.Status(status)(ApiError.body(errors))

object ApiError:
  def apply(status: Int, field: String, message: String): ApiError = ApiError(status, Seq(field -> message))

  def body(errors: Seq[(String, String)]): JsObject =
    val grouped = errors.groupMap(_._1)(_._2)
    val ordered = errors.map(_._1).distinct.map(f => f -> Json.toJson(grouped(f)))
    Json.obj("errors" -> JsObject(ordered))

  def validation(errors: Seq[(String, String)]): ApiError = ApiError(422, errors)
  def notFound(field: String): ApiError                   = ApiError(404, field, "not found")
  def forbidden(field: String): ApiError                  = ApiError(403, field, "forbidden")
  def taken(field: String): ApiError                      = ApiError(409, field, "has already been taken")
  val tokenMissing: ApiError                              = ApiError(401, "token", "is missing")
  val tokenInvalid: ApiError                              = ApiError(401, "token", "is invalid")
  val invalidCredentials: ApiError                        = ApiError(401, "credentials", "invalid")
  val invalidBody: ApiError                               = ApiError(422, "body", "is invalid")
