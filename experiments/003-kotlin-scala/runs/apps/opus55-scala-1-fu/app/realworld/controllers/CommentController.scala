package realworld.controllers

import play.api.mvc.*
import realworld.http.*
import realworld.services.{CommentService, Views}

final class CommentController(cc: ControllerComponents, auth: Authenticator, comments: CommentService)
    extends ApiController(cc, auth) {

  def list(slug: String): Action[?] = open { (_, a) =>
    comments.list(slug, a.map(_.user.id)).map(cs => Ok(Views.comments(cs)))
  }

  def create(slug: String): Action[?] = secured { (req, a) =>
    val in = JsonInput.envelope(req.body, "comment")
    val errors = Errors()
    val body = JsonInput.required(in, "body", errors)
    errors.throwIfAny()
    comments.create(slug, a.user, body).map(c => Created(Views.comment(c)))
  }

  def update(slug: String, id: Long): Action[?] = secured { (req, a) =>
    val in = JsonInput.envelope(req.body, "comment")
    val errors = Errors()
    val body = JsonInput.required(in, "body", errors)
    errors.throwIfAny()
    comments.update(slug, id, a.user, body).map(c => Ok(Views.comment(c)))
  }

  def delete(slug: String, id: Long): Action[?] = secured { (_, a) =>
    comments.delete(slug, id, a.user).map(_ => NoContent)
  }
}
