package realworld.http

import org.apache.pekko.util.ByteString
import play.api.libs.json.*

import scala.util.Try

/** Tri-state value of an optional JSON field in a partial update: absent, explicit null, or a value. */
enum Field[+A] {
  case Absent
  case Null
  case Value(value: A)

  def toOption: Option[A] = this match {
    case Value(v) => Some(v)
    case _        => None
  }
}

/** Helpers to read RealWorld request envelopes such as `{"user": {...}}`. */
object JsonInput {

  /** Parses the body and returns the object under `root`, or fails with a 422 error. */
  def envelope(body: ByteString, root: String): JsObject = {
    val json = Try(Json.parse(body.toArrayUnsafe())).getOrElse(throw ApiError.validation("body", "is not valid JSON"))
    (json \ root).toOption match {
      case Some(o: JsObject) => o
      case _                 => throw ApiError.validation(root, "is missing or not an object")
    }
  }

  def string(obj: JsObject, name: String, errors: Errors): Field[String] =
    obj.value.get(name) match {
      case None              => Field.Absent
      case Some(JsNull)      => Field.Null
      case Some(JsString(s)) => Field.Value(s)
      case Some(_) =>
        errors.add(name, "must be a string")
        Field.Absent
    }

  def stringList(obj: JsObject, name: String, errors: Errors): Field[Seq[String]] =
    obj.value.get(name) match {
      case None         => Field.Absent
      case Some(JsNull) => Field.Null
      case Some(JsArray(items)) if items.forall(_.isInstanceOf[JsString]) =>
        Field.Value(items.collect { case JsString(s) => s }.toSeq)
      case Some(_) =>
        errors.add(name, "must be an array of strings")
        Field.Absent
    }

  /** A required, non-blank string (used on create): reports "can't be blank" when absent, null or blank. */
  def required(obj: JsObject, name: String, errors: Errors): String =
    obj.value.get(name) match {
      case Some(JsString(s)) if s.trim.nonEmpty => s
      case None | Some(JsNull) | Some(JsString(_)) =>
        errors.add(name, "can't be blank")
        ""
      case Some(_) =>
        errors.add(name, "must be a string")
        ""
    }
}
