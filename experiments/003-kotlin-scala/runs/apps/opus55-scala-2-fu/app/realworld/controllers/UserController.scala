package realworld.controllers

import play.api.libs.json.*
import play.api.mvc.*
import realworld.ApiError
import realworld.auth.{Jwt, PasswordHasher}
import realworld.services.{UserService, UserUpdate}

import scala.concurrent.Future

class UserController(cc: ControllerComponents, jwt: Jwt, users: UserService)
    extends ApiController(cc, jwt, users):

  private def blank(obj: JsObject, name: String): Option[(String, String)] =
    if stringField(obj, name).forall(_.trim.isEmpty) then Some(name -> "can't be blank") else None

  def register: Action[String] = Action.async(jsonBody) { req =>
    handle {
      val obj    = rootObject(req.body, "user")
      val errors = blank(obj, "username").toSeq ++ blank(obj, "email") ++
        ApiController.validatePassword(stringField(obj, "password"))
      if errors.nonEmpty then Future.failed(ApiError.validation(errors))
      else
        val hash = PasswordHasher.hash(stringField(obj, "password").get)
        users
          .register(stringField(obj, "username").get.trim, stringField(obj, "email").get.trim, hash)
          .map(u => Created(Views.user(u, jwt.issue(u.id))))
    }
  }

  def login: Action[String] = Action.async(jsonBody) { req =>
    handle {
      val obj    = rootObject(req.body, "user")
      val errors = blank(obj, "email").toSeq ++
        (if stringField(obj, "password").forall(_.isEmpty) then Seq("password" -> "can't be blank") else Nil)
      if errors.nonEmpty then Future.failed(ApiError.validation(errors))
      else
        val password = stringField(obj, "password").get
        users.findByEmail(stringField(obj, "email").get.trim).map {
          case Some(u) if PasswordHasher.verify(password, u.passwordHash) => Ok(Views.user(u, jwt.issue(u.id)))
          case _                                                            => ApiError.invalidCredentials.toResult
        }
    }
  }

  def current: Action[AnyContent] = Action.async { req =>
    handle(requireUser(req).map((u, token) => Ok(Views.user(u, token))))
  }

  def update: Action[String] = Action.async(jsonBody) { req =>
    handle {
      requireUser(req).flatMap { (user, token) =>
        val obj = rootObject(req.body, "user")

        // Required fields: may be omitted, but not set to null or an empty string.
        def required(name: String): (Option[String], Option[(String, String)]) = field(obj, name) match
          case None                                  => (None, None)
          case Some(JsString(s)) if s.trim.nonEmpty => (Some(s.trim), None)
          case Some(_)                               => (None, Some(name -> "can't be blank"))

        // Nullable fields: an empty string is stored as null.
        def nullable(name: String): (Option[Option[String]], Option[(String, String)]) = field(obj, name) match
          case None                => (None, None)
          case Some(JsNull)        => (Some(None), None)
          case Some(JsString(s))   => (Some(Option(s).filter(_.nonEmpty)), None)
          case Some(_)             => (None, Some(name -> "is invalid"))

        val (username, e1) = required("username")
        val (email, e2)    = required("email")
        val (bio, e3)      = nullable("bio")
        val (image, e4)    = nullable("image")
        val (password, e5) = field(obj, "password") match
          case None => (None, None)
          case Some(js) =>
            val p = js.asOpt[String]
            (p, ApiController.validatePassword(p))

        val errors = Seq(e1, e2, e3, e4, e5).flatten
        if errors.nonEmpty then Future.failed(ApiError.validation(errors))
        else
          val change = UserUpdate(username, email, password.map(PasswordHasher.hash), bio, image)
          users.update(user.id, change).map(u => Ok(Views.user(u, token)))
      }
    }
  }
