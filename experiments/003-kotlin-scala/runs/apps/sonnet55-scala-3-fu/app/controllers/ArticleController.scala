package controllers

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.libs.json.*
import play.api.mvc.*
import services.{ArticleView, Repo, Views}
import common.{ApiException, Jwt}

@Singleton
class ArticleController @Inject() (val controllerComponents: ControllerComponents, repo: Repo, config: Configuration)
    extends ApiSupport:
  protected val jwt: Jwt = Jwt(config.get[String]("app.jwt.secret"), config.get[Int]("app.jwt.ttlDays") * 86400L)

  private def single(a: ArticleView) = Json.obj("article" -> Views.article(a, withBody = true))

  private def multiple(page: (Seq[ArticleView], Int)) =
    Ok(Json.obj("articles" -> page._1.map(Views.article(_, withBody = false)), "articlesCount" -> page._2))

  /** `tagList` must be an array of strings when present. */
  private def tagList(o: JsObject): Option[Seq[String]] =
    o.value.get("tagList").map {
      case JsArray(items) => items.map {
        case JsString(s) => s
        case _ => throw ApiException(422, "tagList", "is invalid")
      }.toSeq
      case _ => throw ApiException(422, "tagList", "is invalid")
    }

  def list: Action[AnyContent] = Action.async { req =>
    handle {
      val viewer = optionalUser(req)
      repo.listArticles(req.getQueryString("tag"), req.getQueryString("author"), req.getQueryString("favorited"),
        intParam(req, "limit", 20), intParam(req, "offset", 0), viewer).map(multiple)
    }
  }

  def feed: Action[AnyContent] = Action.async { req =>
    handle(repo.feed(requireUser(req), intParam(req, "limit", 20), intParam(req, "offset", 0)).map(multiple))
  }

  def get(slug: String): Action[AnyContent] = Action.async { req =>
    handle(repo.article(slug, optionalUser(req)).map(a => Ok(single(a))))
  }

  def create: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    handle {
      val userId = requireUser(req)
      val a = wrapper(req, "article")
      val (title, description, body) = (required(a, "title"), required(a, "description"), required(a, "body"))
      failIfAny(Seq(title, description, body).collect { case Left(e) => e })
      repo.createArticle(userId, title.toOption.get, description.toOption.get, body.toOption.get, tagList(a).getOrElse(Nil))
        .map(v => Created(single(v)))
    }
  }

  def update(slug: String): Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    handle {
      val userId = requireUser(req)
      val a = wrapper(req, "article")
      val errors = Seq("title", "description", "body").flatMap { f =>
        a.value.get(f).collect { case v if !v.asOpt[String].exists(_.trim.nonEmpty) => f -> Seq("can't be blank") }
      }
      failIfAny(errors)
      repo.updateArticle(slug, userId, a.value.get("title").flatMap(_.asOpt[String]), a.value.get("description").flatMap(_.asOpt[String]),
        a.value.get("body").flatMap(_.asOpt[String]), tagList(a)).map(v => Ok(single(v)))
    }
  }

  def delete(slug: String): Action[AnyContent] = Action.async { req =>
    handle(repo.deleteArticle(slug, requireUser(req)).map(_ => NoContent))
  }

  def favorite(slug: String): Action[AnyContent] = Action.async { req =>
    handle(repo.setFavorite(slug, requireUser(req), favorite = true).map(a => Ok(single(a))))
  }

  def unfavorite(slug: String): Action[AnyContent] = Action.async { req =>
    handle(repo.setFavorite(slug, requireUser(req), favorite = false).map(a => Ok(single(a))))
  }
