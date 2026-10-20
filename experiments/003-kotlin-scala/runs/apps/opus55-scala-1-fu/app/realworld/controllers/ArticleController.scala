package realworld.controllers

import play.api.mvc.*
import realworld.http.*
import realworld.services.{ArticleFilter, ArticlePatch, ArticleService, Views}

final class ArticleController(cc: ControllerComponents, auth: Authenticator, articles: ArticleService)
    extends ApiController(cc, auth) {

  private def paging(req: RequestHeader): (Int, Int) =
    (intParam(req, "limit", 20, 0, 1000), intParam(req, "offset", 0, 0, Int.MaxValue))

  def list: Action[?] = open { (req, a) =>
    val (limit, offset) = paging(req)
    def param(n: String) = req.getQueryString(n).map(_.trim).filter(_.nonEmpty)
    val filter = ArticleFilter(param("tag"), param("author"), param("favorited"))
    articles.list(filter, limit, offset, a.map(_.user.id)).map((list, count) => Ok(Views.articles(list, count)))
  }

  def feed: Action[?] = secured { (req, a) =>
    val (limit, offset) = paging(req)
    articles.feed(a.user.id, limit, offset).map((list, count) => Ok(Views.articles(list, count)))
  }

  def get(slug: String): Action[?] = open { (_, a) =>
    articles.get(slug, a.map(_.user.id)).map(v => Ok(Views.article(v)))
  }

  def create: Action[?] = secured { (req, a) =>
    val in = JsonInput.envelope(req.body, "article")
    val errors = Errors()
    val title = JsonInput.required(in, "title", errors)
    val description = JsonInput.required(in, "description", errors)
    val body = JsonInput.required(in, "body", errors)
    val tagList = JsonInput.stringList(in, "tagList", errors).toOption.getOrElse(Seq.empty)
    errors.throwIfAny()
    articles.create(a.user, title, description, body, tagList).map(v => Created(Views.article(v)))
  }

  def update(slug: String): Action[?] = secured { (req, a) =>
    val in = JsonInput.envelope(req.body, "article")
    val errors = Errors()
    def nonBlank(name: String): Option[String] = JsonInput.string(in, name, errors) match {
      case Field.Value(v) if v.trim.nonEmpty => Some(v)
      case Field.Absent                       => None
      case _ =>
        errors.add(name, "can't be blank")
        None
    }
    val title = nonBlank("title")
    val description = nonBlank("description")
    val body = nonBlank("body")
    val tagList = JsonInput.stringList(in, "tagList", errors) match {
      case Field.Value(t) => Some(t)
      case Field.Absent   => None
      case Field.Null =>
        errors.add("tagList", "must be an array of strings")
        None
    }
    errors.throwIfAny()
    articles.update(slug, a.user, ArticlePatch(title, description, body, tagList)).map(v => Ok(Views.article(v)))
  }

  def delete(slug: String): Action[?] = secured { (_, a) =>
    articles.delete(slug, a.user).map(_ => NoContent)
  }

  def favorite(slug: String): Action[?] = secured { (_, a) =>
    articles.favorite(slug, a.user).map(v => Ok(Views.article(v)))
  }

  def unfavorite(slug: String): Action[?] = secured { (_, a) =>
    articles.unfavorite(slug, a.user).map(v => Ok(Views.article(v)))
  }
}
