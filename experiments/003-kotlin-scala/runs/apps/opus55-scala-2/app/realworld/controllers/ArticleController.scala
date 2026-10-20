package realworld.controllers

import play.api.libs.json.*
import play.api.mvc.*
import realworld.ApiError
import realworld.auth.Jwt
import realworld.services.{ArticleFilter, ArticleService, ArticleUpdate, UserService}

import scala.concurrent.Future

class ArticleController(cc: ControllerComponents, jwt: Jwt, users: UserService, articles: ArticleService)
    extends ApiController(cc, jwt, users):

  private def page(limit: Option[Int], offset: Option[Int]): (Int, Int) =
    (limit.getOrElse(20).max(0).min(1000), offset.getOrElse(0).max(0))

  def list(
      tag: Option[String],
      author: Option[String],
      favorited: Option[String],
      limit: Option[Int],
      offset: Option[Int]
  ): Action[AnyContent] = Action.async { req =>
    handle {
      val (l, o) = page(limit, offset)
      for
        viewer          <- optionalUser(req)
        (views, count) <- articles.list(ArticleFilter(tag, author, favorited), l, o, viewer.map(_.id))
      yield Ok(Views.articles(views, count))
    }
  }

  def feed(limit: Option[Int], offset: Option[Int]): Action[AnyContent] = Action.async { req =>
    handle {
      val (l, o) = page(limit, offset)
      for
        (me, _)         <- requireUser(req)
        (views, count) <- articles.list(ArticleFilter(feedOf = Some(me.id)), l, o, Some(me.id))
      yield Ok(Views.articles(views, count))
    }
  }

  def get(slug: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        viewer <- optionalUser(req)
        view   <- articles.get(slug, viewer.map(_.id))
      yield Ok(Views.article(view))
    }
  }

  /** Parses tagList: absent is None, an array of strings is Some, anything else (including null) is invalid. */
  private def tagList(obj: JsObject): Either[(String, String), Option[Seq[String]]] =
    field(obj, "tagList") match
      case None => Right(None)
      case Some(JsArray(items)) if items.forall(_.isInstanceOf[JsString]) =>
        Right(Some(items.collect { case JsString(s) => s }.toSeq))
      case Some(_) => Left("tagList" -> "must be an array of strings")

  def create: Action[String] = Action.async(jsonBody) { req =>
    handle {
      requireUser(req).flatMap { (me, _) =>
        val obj = rootObject(req.body, "article")
        def required(name: String) =
          if stringField(obj, name).forall(_.trim.isEmpty) then Some(name -> "can't be blank") else None
        val tags   = tagList(obj)
        val errors = Seq(required("title"), required("description"), required("body"), tags.left.toOption).flatten
        if errors.nonEmpty then Future.failed(ApiError.validation(errors))
        else
          articles
            .create(
              me.id,
              stringField(obj, "title").get,
              stringField(obj, "description").get,
              stringField(obj, "body").get,
              tags.toOption.flatten.getOrElse(Seq.empty)
            )
            .map(v => Created(Views.article(v)))
      }
    }
  }

  def update(slug: String): Action[String] = Action.async(jsonBody) { req =>
    handle {
      requireUser(req).flatMap { (me, _) =>
        val obj = rootObject(req.body, "article")
        // Fields may be omitted, but a present field must be a non-blank string.
        def optional(name: String): (Option[String], Option[(String, String)]) = field(obj, name) match
          case None                                  => (None, None)
          case Some(JsString(s)) if s.trim.nonEmpty => (Some(s), None)
          case Some(_)                               => (None, Some(name -> "can't be blank"))
        val (title, e1)       = optional("title")
        val (description, e2) = optional("description")
        val (body, e3)        = optional("body")
        val tags              = tagList(obj)
        val errors            = Seq(e1, e2, e3, tags.left.toOption).flatten
        if errors.nonEmpty then Future.failed(ApiError.validation(errors))
        else
          articles
            .update(slug, me.id, ArticleUpdate(title, description, body, tags.toOption.flatten))
            .map(v => Ok(Views.article(v)))
      }
    }
  }

  def delete(slug: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        (me, _) <- requireUser(req)
        _       <- articles.delete(slug, me.id)
      yield NoContent
    }
  }

  def favorite(slug: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        (me, _) <- requireUser(req)
        view    <- articles.favorite(slug, me.id)
      yield Ok(Views.article(view))
    }
  }

  def unfavorite(slug: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        (me, _) <- requireUser(req)
        view    <- articles.unfavorite(slug, me.id)
      yield Ok(Views.article(view))
    }
  }
