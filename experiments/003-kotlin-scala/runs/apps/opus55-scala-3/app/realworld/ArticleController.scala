package realworld

import play.api.libs.json.*
import play.api.mvc.*

import scala.concurrent.{ExecutionContext, Future}

final class ArticleController(cc: ControllerComponents, jwt: Jwt, repo: Repo)(using ExecutionContext) extends ApiController(cc, jwt):

  private def paging(req: RequestHeader): (Int, Int) =
    val limit = req.getQueryString("limit").flatMap(_.toIntOption).filter(_ >= 0).map(_.min(1000)).getOrElse(20)
    val offset = req.getQueryString("offset").flatMap(_.toIntOption).filter(_ >= 0).getOrElse(0)
    (limit, offset)

  private def param(req: RequestHeader, name: String): Option[String] =
    req.getQueryString(name).map(_.trim).filter(_.nonEmpty)

  private def render(a: ArticleView, status: Status = Ok): Result = status(Json.obj("article" -> Render.article(a)))

  /** Parses `tagList`: absent is None, an array of strings is Some, anything else (including null) is invalid. */
  private def tagList(o: JsObject): Either[Result, Option[Seq[String]]] = o \ "tagList" match
    case _: JsUndefined => Right(None)
    case JsDefined(JsArray(items)) if items.forall(_.isInstanceOf[JsString]) =>
      Right(Some(items.collect { case JsString(s) => s.trim }.filter(_.nonEmpty).toSeq))
    case _ => Left(errors(UnprocessableEntity, "tagList" -> "must be an array of strings"))

  def list: Action[AnyContent] = maybeAuthed { (req, viewer) =>
    val (limit, offset) = paging(req)
    repo.list(param(req, "tag"), param(req, "author"), param(req, "favorited"), limit, offset, viewer)
      .map(p => Ok(Render.articles(p)))
  }

  def feed: Action[AnyContent] = authed { (req, id) =>
    val (limit, offset) = paging(req)
    repo.feed(id, limit, offset).map(p => Ok(Render.articles(p)))
  }

  def get(slug: String): Action[AnyContent] = maybeAuthed { (_, viewer) =>
    repo.article(slug, viewer).map(_.fold(notFound("article"))(render(_)))
  }

  def create: Action[AnyContent] = authed { (req, id) =>
    bodyObject(req, "article").fold(done, o =>
      val (title, description, body) = (str(o, "title"), str(o, "description"), str(o, "body"))
      val missing = Seq("title" -> title, "description" -> description, "body" -> body).collect { case (k, None) => k }
      if missing.nonEmpty then done(blank(missing*))
      else
        tagList(o).fold(done, tags =>
          repo.createArticle(id, title.get, description.get, body.get, tags.getOrElse(Seq.empty)).map(render(_, Created))))
  }

  def update(slug: String): Action[AnyContent] = authed { (req, id) =>
    repo.findArticle(slug).flatMap {
      case None => done(notFound("article"))
      case Some(a) if a.authorId != id => done(forbidden("article"))
      case Some(a) =>
        bodyObject(req, "article").fold(done, o =>
          def field(name: String): Either[(String, String), Option[String]] = o \ name match
            case JsDefined(JsString(s)) if s.trim.nonEmpty => Right(Some(s))
            case _: JsUndefined => Right(None)
            case _ => Left(name -> "can't be blank")
          val fields = Seq(field("title"), field("description"), field("body"))
          val problems = fields.collect { case Left(e) => e }
          if problems.nonEmpty then done(errors(UnprocessableEntity, problems*))
          else
            tagList(o).fold(done, tags =>
              val Seq(title, description, body) = fields.map(_.toOption.flatten)
              repo.updateArticle(a, ArticlePatch(title, description, body, tags)).map(render(_))))
    }
  }

  def delete(slug: String): Action[AnyContent] = authed { (_, id) =>
    repo.findArticle(slug).flatMap {
      case None => done(notFound("article"))
      case Some(a) if a.authorId != id => done(forbidden("article"))
      case Some(a) => repo.deleteArticle(a.id).map(_ => NoContent)
    }
  }

  def favorite(slug: String): Action[AnyContent] = authed { (_, id) =>
    repo.setFavorite(id, slug, favorite = true).map(_.fold(notFound("article"))(render(_)))
  }

  def unfavorite(slug: String): Action[AnyContent] = authed { (_, id) =>
    repo.setFavorite(id, slug, favorite = false).map(_.fold(notFound("article"))(render(_)))
  }

final class CommentController(cc: ControllerComponents, jwt: Jwt, repo: Repo)(using ExecutionContext) extends ApiController(cc, jwt):

  def list(slug: String): Action[AnyContent] = maybeAuthed { (_, viewer) =>
    repo.findArticle(slug).flatMap {
      case None => done(notFound("article"))
      case Some(a) => repo.comments(a.id, viewer).map(cs => Ok(Json.obj("comments" -> cs.map(Render.comment))))
    }
  }

  def create(slug: String): Action[AnyContent] = authed { (req, id) =>
    repo.findArticle(slug).flatMap {
      case None => done(notFound("article"))
      case Some(a) =>
        bodyObject(req, "comment").fold(done, o =>
          str(o, "body") match
            case None => done(blank("body"))
            case Some(body) => repo.createComment(a.id, id, body).map(c => Created(Json.obj("comment" -> Render.comment(c)))))
    }
  }

  def delete(slug: String, commentId: Long): Action[AnyContent] = authed { (_, id) =>
    repo.findArticle(slug).flatMap {
      case None => done(notFound("article"))
      case Some(a) =>
        repo.commentAuthor(a.id, commentId).flatMap {
          case None => done(notFound("comment"))
          case Some(author) if author != id => done(forbidden("comment"))
          case Some(_) => repo.deleteComment(commentId).map(_ => NoContent)
        }
    }
  }
