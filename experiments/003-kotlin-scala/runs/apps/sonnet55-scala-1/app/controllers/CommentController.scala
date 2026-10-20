package controllers

import models.*
import models.Tables.*
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.*
import play.api.mvc.*
import services.Security
import slick.jdbc.SQLiteProfile.api.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CommentController @Inject() (cc: ControllerComponents, dbp: DatabaseConfigProvider, security: Security)(using ExecutionContext)
    extends ApiSupport(cc, dbp, security) {

  private def articleId(slug: String): DBIO[Option[Long]] =
    articles.filter(_.slug === slug).map(_.id).result.headOption

  def list(slug: String): Action[AnyContent] = Action.async { request =>
    withOptAuth(request) { me =>
      db.run(articleId(slug).flatMap {
        case None => DBIO.successful(notFound("article"))
        case Some(aid) =>
          for {
            rows <- comments.filter(_.articleId === aid).sortBy(c => (c.createdAt.desc, c.id.desc)).result
            authors <- users.filter(_.id.inSet(rows.map(_.authorId).toSet)).result.map(_.map(u => u.id -> u).toMap)
            followed <- me match {
              case Some(m) => follows.filter(f => f.followerId === m.id && f.followeeId.inSet(authors.keySet)).map(_.followeeId).result.map(_.toSet)
              case None => DBIO.successful(Set.empty[Long])
            }
          } yield Ok(Json.obj("comments" -> rows.map(c => commentView(c, authors(c.authorId), followed.contains(c.authorId)))))
      })
    }
  }

  def add(slug: String): Action[JsValue] = Action.async(parse.json) { request =>
    withAuth(request) { (me, _) =>
      val body = (request.body \ "comment" \ "body").asOpt[String]
      if (blank(body)) Future.successful(invalid("body" -> "can't be blank"))
      else
        db.run((articleId(slug).flatMap {
          case None => DBIO.successful(notFound("article"))
          case Some(aid) =>
            val ts = now()
            (comments.map(c => (c.articleId, c.authorId, c.body, c.createdAt, c.updatedAt)) returning comments.map(_.id) +=
              ((aid, me.id, body.get, ts, ts))).map { id =>
              Created(Json.obj("comment" -> commentView(CommentRow(id, aid, me.id, body.get, ts, ts), me, false)))
            }
        }).transactionally)
    }
  }

  def delete(slug: String, id: Long): Action[AnyContent] = Action.async { request =>
    withAuth(request) { (me, _) =>
      db.run((articleId(slug).flatMap {
        case None => DBIO.successful(notFound("article"))
        case Some(aid) =>
          comments.filter(c => c.id === id && c.articleId === aid).result.headOption.flatMap {
            case None => DBIO.successful(notFound("comment"))
            case Some(c) if c.authorId != me.id => DBIO.successful(forbidden("comment"))
            case Some(c) => comments.filter(_.id === c.id).delete.map(_ => NoContent)
          }
      }).transactionally)
    }
  }
}
