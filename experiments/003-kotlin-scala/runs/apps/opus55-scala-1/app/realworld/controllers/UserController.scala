package realworld.controllers

import play.api.mvc.*
import realworld.auth.Jwt
import realworld.http.*
import realworld.services.{UserPatch, UserService, Views}

import scala.concurrent.Future

final class UserController(cc: ControllerComponents, auth: Authenticator, users: UserService, jwt: Jwt)
    extends ApiController(cc, auth) {
  import UserController.*

  def register: Action[?] = open { (req, _) =>
    val in = JsonInput.envelope(req.body, "user")
    val errors = Errors()
    val username = JsonInput.required(in, "username", errors).trim
    val email = JsonInput.required(in, "email", errors).trim
    val password = JsonInput.required(in, "password", errors)
    if (email.nonEmpty && !EmailPattern.matches(email)) errors.add("email", "is invalid")
    if (password.nonEmpty && password.length < MinPassword) errors.add("password", tooShort)
    errors.throwIfAny()
    users.register(username, email, password).map(u => Created(Views.user(u, jwt.issue(u.id, u.username))))
  }

  def login: Action[?] = open { (req, _) =>
    val in = JsonInput.envelope(req.body, "user")
    val errors = Errors()
    val email = JsonInput.required(in, "email", errors).trim
    val password = JsonInput.required(in, "password", errors)
    errors.throwIfAny()
    users.login(email, password).map(u => Ok(Views.user(u, jwt.issue(u.id, u.username))))
  }

  def current: Action[?] = secured { (_, a) =>
    Future.successful(Ok(Views.user(a.user, a.token)))
  }

  def update: Action[?] = secured { (req, a) =>
    val in = JsonInput.envelope(req.body, "user")
    val errors = Errors()
    def nonBlank(name: String): Field[String] = JsonInput.string(in, name, errors) match {
      case Field.Value(v) if v.trim.nonEmpty => Field.Value(v.trim)
      case Field.Absent                       => Field.Absent
      case _ =>
        errors.add(name, "can't be blank")
        Field.Absent
    }
    val username = nonBlank("username")
    val email = nonBlank("email")
    email.toOption.foreach(e => if (!EmailPattern.matches(e)) errors.add("email", "is invalid"))
    val password = JsonInput.string(in, "password", errors) match {
      case Field.Value(p) if p.length >= MinPassword => Field.Value(p)
      case Field.Value(p) if p.nonEmpty =>
        errors.add("password", tooShort)
        Field.Absent
      case Field.Absent => Field.Absent
      case _ =>
        errors.add("password", "can't be blank")
        Field.Absent
    }
    val bio = JsonInput.string(in, "bio", errors)
    val image = JsonInput.string(in, "image", errors)
    errors.throwIfAny()
    users
      .update(a.user, UserPatch(username, email, password, bio, image))
      .map(u => Ok(Views.user(u, jwt.issue(u.id, u.username))))
  }
}

object UserController {
  /** NIST SP 800-63B: memorized secrets must be at least 8 characters; no composition rules. */
  val MinPassword = 8
  private val tooShort = s"is too short (minimum is $MinPassword characters)"
  private val EmailPattern = "^[^@\\s]+@[^@\\s]+$".r
}
