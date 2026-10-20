package controllers

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.libs.json.*
import play.api.mvc.*
import services.{ProfileView, Repo, Views}
import common.Jwt

@Singleton
class ProfileController @Inject() (val controllerComponents: ControllerComponents, repo: Repo, config: Configuration)
    extends ApiSupport:
  protected val jwt: Jwt = Jwt(config.get[String]("app.jwt.secret"), config.get[Int]("app.jwt.ttlDays") * 86400L)

  private def respond(p: ProfileView) = Ok(Json.obj("profile" -> Views.profile(p)))

  def get(username: String): Action[AnyContent] = Action.async { req =>
    handle(repo.profile(username, optionalUser(req)).map(respond))
  }

  def follow(username: String): Action[AnyContent] = Action.async { req =>
    handle(repo.setFollow(username, requireUser(req), follow = true).map(respond))
  }

  def unfollow(username: String): Action[AnyContent] = Action.async { req =>
    handle(repo.setFollow(username, requireUser(req), follow = false).map(respond))
  }
