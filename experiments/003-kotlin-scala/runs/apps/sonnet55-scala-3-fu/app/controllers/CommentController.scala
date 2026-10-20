package controllers

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.libs.json.*
import play.api.mvc.*
import services.{Repo, Views}
import common.Jwt

@Singleton
class CommentController @Inject() (val controllerComponents: ControllerComponents, repo: Repo, config: Configuration)
    extends ApiSupport:
  protected val jwt: Jwt = Jwt(config.get[String]("app.jwt.secret"), config.get[Int]("app.jwt.ttlDays") * 86400L)

  def list(slug: String): Action[AnyContent] = Action.async { req =>
    handle(repo.commentsOf(slug, optionalUser(req)).map(cs => Ok(Json.obj("comments" -> cs.map(Views.comment)))))
  }

  def create(slug: String): Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    handle {
      val userId = requireUser(req)
      val body = required(wrapper(req, "comment"), "body")
      failIfAny(Seq(body).collect { case Left(e) => e })
      repo.addComment(slug, userId, body.toOption.get).map(c => Created(Json.obj("comment" -> Views.comment(c))))
    }
  }

  def update(slug: String, id: Long): Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    handle {
      val userId = requireUser(req)
      val body = required(wrapper(req, "comment"), "body")
      failIfAny(Seq(body).collect { case Left(e) => e })
      repo.updateComment(slug, id, userId, body.toOption.get).map(c => Ok(Json.obj("comment" -> Views.comment(c))))
    }
  }

  def delete(slug: String, id: Long): Action[AnyContent] = Action.async { req =>
    handle(repo.deleteComment(slug, id, requireUser(req)).map(_ => NoContent))
  }
