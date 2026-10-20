package common

/** A failure that maps directly onto a RealWorld error response: `{"errors": {field: [messages]}}`. */
final case class ApiException(status: Int, errors: Map[String, Seq[String]]) extends RuntimeException(errors.toString)

object ApiException:
  def apply(status: Int, field: String, message: String): ApiException =
    ApiException(status, Map(field -> Seq(message)))

  def notFound(field: String): ApiException = ApiException(404, field, "not found")
  def forbidden(field: String): ApiException = ApiException(403, field, "forbidden")
  def blank(field: String): ApiException = ApiException(422, field, "can't be blank")
