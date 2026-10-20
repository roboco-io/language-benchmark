package controllers

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.libs.json.*
import play.api.mvc.*
import services.{Repo, Views}
import common.{ApiException, Jwt}

import scala.concurrent.Future

@Singleton
class UserController @Inject() (val controllerComponents: ControllerComponents, repo: Repo, config: Configuration)
    extends ApiSupport:
  protected val jwt: Jwt = Jwt(config.get[String]("app.jwt.secret"), config.get[Int]("app.jwt.ttlDays") * 86400L)

  private val MinPassword = 8

  private def respond(u: services.UserRow) = Ok(Json.obj("user" -> Views.user(u, jwt.issue(u.id))))

  private def passwordLength(pw: String): Seq[(String, Seq[String])] =
    if pw.length < MinPassword then Seq("password" -> Seq(s"is too short (minimum is $MinPassword characters)")) else Nil

  def register: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    handle {
      val u = wrapper(req, "user")
      val (username, email, password) = (required(u, "username"), required(u, "email"), required(u, "password"))
      failIfAny(Seq(username, email, password).collect { case Left(e) => e } ++ password.toOption.fold(Nil)(passwordLength))
      repo.register(username.toOption.get.trim, email.toOption.get.trim, password.toOption.get).map { user =>
        Created(Json.obj("user" -> Views.user(user, jwt.issue(user.id))))
      }
    }
  }

  def login: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    handle {
      val u = wrapper(req, "user")
      val (email, password) = (required(u, "email"), required(u, "password"))
      failIfAny(Seq(email, password).collect { case Left(e) => e })
      repo.authenticate(email.toOption.get.trim, password.toOption.get).map(respond)
    }
  }

  def current: Action[AnyContent] = Action.async { req =>
    handle {
      repo.userById(requireUser(req)).map {
        case Some(u) => respond(u)
        case None => throw ApiException(401, "token", "is invalid")
      }
    }
  }

  def update: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    handle {
      val id = requireUser(req)
      val u = wrapper(req, "user")
      val password = presentNonBlank(u, "password")
      failIfAny(password.fold(Nil)(passwordLength))
      repo.updateUser(id, presentNonBlank(u, "username").map(_.trim), presentNonBlank(u, "email").map(_.trim), password,
        nullable(u, "bio"), nullable(u, "image")).map(respond)
    }
  }
