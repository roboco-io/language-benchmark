package realworld.controllers

import play.api.mvc.*
import realworld.ApiError
import realworld.auth.Jwt
import realworld.services.{ArticleService, UserService}

import scala.concurrent.Future

class CommentController(cc: ControllerComponents, jwt: Jwt, users: UserService, articles: ArticleService)
    extends ApiController(cc, jwt, users):

  def list(slug: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        viewer   <- optionalUser(req)
        comments <- articles.comments(slug, viewer.map(_.id))
      yield Ok(Views.comments(comments))
    }
  }

  def create(slug: String): Action[String] = Action.async(jsonBody) { req =>
    handle {
      requireUser(req).flatMap { (me, _) =>
        val obj = rootObject(req.body, "comment")
        stringField(obj, "body").filter(_.trim.nonEmpty) match
          case None       => Future.failed(ApiError.validation(Seq("body" -> "can't be blank")))
          case Some(body) => articles.addComment(slug, me.id, body).map(c => Created(Views.comment(c)))
      }
    }
  }

  def delete(slug: String, id: Long): Action[AnyContent] = Action.async { req =>
    handle {
      for
        (me, _) <- requireUser(req)
        _       <- articles.deleteComment(slug, id, me.id)
      yield NoContent
    }
  }

class TagController(cc: ControllerComponents, jwt: Jwt, users: UserService, articles: ArticleService)
    extends ApiController(cc, jwt, users):

  def list: Action[AnyContent] = Action.async {
    articles.tagNames().map(tags => Ok(play.api.libs.json.Json.obj("tags" -> tags)))
  }
