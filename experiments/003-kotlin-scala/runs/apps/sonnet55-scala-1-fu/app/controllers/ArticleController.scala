package controllers

import models.*
import models.Tables.*
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.*
import play.api.mvc.*
import services.Security
import slick.jdbc.SQLiteProfile.api.*

import java.security.SecureRandom
import java.text.Normalizer
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ArticleController @Inject() (cc: ControllerComponents, dbp: DatabaseConfigProvider, security: Security)(using ExecutionContext)
    extends ApiSupport(cc, dbp, security) {

  private val rng = new SecureRandom()

  private def slugify(title: String): String = {
    val base = Normalizer.normalize(title, Normalizer.Form.NFKD).toLowerCase
      .replaceAll("[^\\p{Alnum}]+", "-").replaceAll("^-+|-+$", "")
    val suffix = Integer.toHexString(rng.nextInt()).takeRight(6).reverse.padTo(6, '0').reverse
    if (base.isEmpty) suffix else s"$base-$suffix"
  }

  private def single(a: ArticleRow, me: Option[UserRow]): DBIO[JsObject] =
    articleViews(Seq(a), me, withBody = true).map(vs => Json.obj("article" -> vs.head))

  private def intParam(request: RequestHeader, name: String, default: Int, max: Int): Int =
    request.getQueryString(name).flatMap(_.toIntOption).filter(_ >= 0).map(math.min(_, max)).getOrElse(default)

  private def page(query: Query[Articles, ArticleRow, Seq], request: RequestHeader, me: Option[UserRow]): DBIO[Result] = {
    val limit = intParam(request, "limit", 20, 1000)
    val offset = intParam(request, "offset", 0, Int.MaxValue)
    for {
      total <- query.length.result
      rows <- query.sortBy(a => (a.createdAt.desc, a.id.desc)).drop(offset).take(limit).result
      views <- articleViews(rows, me, withBody = false)
    } yield Ok(Json.obj("articles" -> views, "articlesCount" -> total))
  }

  def list: Action[AnyContent] = Action.async { request =>
    withOptAuth(request) { me =>
      var q: Query[Articles, ArticleRow, Seq] = articles
      request.getQueryString("tag").foreach { t =>
        q = q.filter(a => articleTags.filter(x => x.articleId === a.id && x.name === t).exists)
      }
      request.getQueryString("author").foreach { n =>
        q = q.filter(a => users.filter(u => u.id === a.authorId && u.username === n).exists)
      }
      request.getQueryString("favorited").foreach { n =>
        q = q.filter(a => favorites.filter(f => f.articleId === a.id &&
          users.filter(u => u.id === f.userId && u.username === n).exists).exists)
      }
      db.run(page(q, request, me))
    }
  }

  def feed: Action[AnyContent] = Action.async { request =>
    withAuth(request) { (me, _) =>
      val q = articles.filter(a => follows.filter(f => f.followerId === me.id && f.followeeId === a.authorId).exists)
      db.run(page(q, request, Some(me)))
    }
  }

  def get(slug: String): Action[AnyContent] = Action.async { request =>
    withOptAuth(request) { me =>
      db.run(articles.filter(_.slug === slug).result.headOption.flatMap {
        case None => DBIO.successful(notFound("article"))
        case Some(a) => single(a, me).map(Ok(_))
      })
    }
  }

  private def tagsOf(js: JsLookupResult): Either[Unit, Option[Seq[String]]] = js match {
    case JsDefined(JsArray(items)) =>
      Right(Some(items.collect { case JsString(s) => s.trim }.filter(_.nonEmpty).distinct.toSeq))
    case JsDefined(_) => Left(())
    case _ => Right(None)
  }

  def create: Action[JsValue] = Action.async(parse.json) { request =>
    withAuth(request) { (me, _) =>
      val a = request.body \ "article"
      val title = (a \ "title").asOpt[String]
      val description = (a \ "description").asOpt[String]
      val body = (a \ "body").asOpt[String]
      val tagList = tagsOf(a \ "tagList")
      val errs = Seq(
        if (blank(title)) Some("title" -> "can't be blank") else None,
        if (blank(description)) Some("description" -> "can't be blank") else None,
        if (blank(body)) Some("body" -> "can't be blank") else None,
        if (tagList.isLeft) Some("tagList" -> "is invalid") else None
      ).flatten
      if (errs.nonEmpty) Future.successful(invalid(errs*))
      else {
        val ts = now()
        val row = (slugify(title.get), title.get, description.get, body.get, me.id, ts, ts)
        val action = (for {
          id <- (articles.map(r => (r.slug, r.title, r.description, r.body, r.authorId, r.createdAt, r.updatedAt)) returning articles.map(_.id)) += row
          _ <- articleTags ++= tagList.toOption.flatten.getOrElse(Seq.empty).map(t => (0L, id, t))
          res <- single(ArticleRow(id, row._1, row._2, row._3, row._4, me.id, ts, ts), Some(me))
        } yield Created(res)).transactionally
        db.run(action)
      }
    }
  }

  /** Loads an article by slug and runs `f` on it if the caller is the author. */
  private def asAuthor(slug: String, me: UserRow)(f: ArticleRow => DBIO[Result]): DBIO[Result] =
    articles.filter(_.slug === slug).result.headOption.flatMap {
      case None => DBIO.successful(notFound("article"))
      case Some(a) if a.authorId != me.id => DBIO.successful(forbidden("article"))
      case Some(a) => f(a)
    }

  def update(slug: String): Action[JsValue] = Action.async(parse.json) { request =>
    withAuth(request) { (me, _) =>
      val a = request.body \ "article"
      def text(name: String): Either[(String, String), Option[String]] = a \ name match {
        case JsDefined(JsString(s)) if s.trim.nonEmpty => Right(Some(s))
        case JsDefined(_) => Left(name -> "can't be blank")
        case _ => Right(None)
      }
      val tagList = tagsOf(a \ "tagList")
      val checks = Seq(text("title"), text("description"), text("body"))
      val errs = checks.collect { case Left(e) => e } ++ (if (tagList.isLeft) Seq("tagList" -> "is invalid") else Nil)
      if (errs.nonEmpty) Future.successful(invalid(errs*))
      else {
        val Seq(Right(title), Right(description), Right(body)) = checks: @unchecked
        val action = asAuthor(slug, me) { existing =>
          val updated = existing.copy(
            title = title.getOrElse(existing.title),
            description = description.getOrElse(existing.description),
            body = body.getOrElse(existing.body),
            updatedAt = now()
          )
          for {
            _ <- articles.filter(_.id === existing.id).update(updated)
            _ <- tagList.toOption.flatten match {
              case Some(tags) =>
                articleTags.filter(_.articleId === existing.id).delete >>
                  (articleTags ++= tags.map(t => (0L, existing.id, t)))
              case None => DBIO.successful(())
            }
            res <- single(updated, Some(me))
          } yield Ok(res)
        }
        db.run(action.transactionally)
      }
    }
  }

  def delete(slug: String): Action[AnyContent] = Action.async { request =>
    withAuth(request) { (me, _) =>
      db.run(asAuthor(slug, me)(a => articles.filter(_.id === a.id).delete.map(_ => NoContent)).transactionally)
    }
  }

  private def setFavorite(slug: String, on: Boolean): Action[AnyContent] = Action.async { request =>
    withAuth(request) { (me, _) =>
      val action = articles.filter(_.slug === slug).result.headOption.flatMap {
        case None => DBIO.successful(notFound("article"))
        case Some(a) =>
          val change =
            if (on) favorites.insertOrUpdate((me.id, a.id))
            else favorites.filter(f => f.userId === me.id && f.articleId === a.id).delete
          change >> single(a, Some(me)).map(Ok(_))
      }
      db.run(action.transactionally)
    }
  }

  def favorite(slug: String): Action[AnyContent] = setFavorite(slug, on = true)
  def unfavorite(slug: String): Action[AnyContent] = setFavorite(slug, on = false)
}
