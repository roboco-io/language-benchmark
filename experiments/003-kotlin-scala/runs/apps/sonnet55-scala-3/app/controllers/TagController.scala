package controllers

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.libs.json.*
import play.api.mvc.*
import services.Repo
import common.Jwt

@Singleton
class TagController @Inject() (val controllerComponents: ControllerComponents, repo: Repo, config: Configuration)
    extends ApiSupport:
  protected val jwt: Jwt = Jwt(config.get[String]("app.jwt.secret"), config.get[Int]("app.jwt.ttlDays") * 86400L)

  def list: Action[AnyContent] = Action.async { _ =>
    handle(repo.tags.map(ts => Ok(Json.obj("tags" -> ts))))
  }
