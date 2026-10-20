package controllers

import models.Tables.*
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.*
import play.api.mvc.*
import services.Security
import slick.jdbc.SQLiteProfile.api.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ProfileController @Inject() (cc: ControllerComponents, dbp: DatabaseConfigProvider, security: Security)(using ExecutionContext)
    extends ApiSupport(cc, dbp, security) {

  def get(username: String): Action[AnyContent] = Action.async { request =>
    withOptAuth(request) { me =>
      db.run(users.filter(_.username === username).result.headOption.flatMap {
        case None => DBIO.successful(notFound("profile"))
        case Some(u) => isFollowing(me, u.id).map(f => Ok(Json.obj("profile" -> profileObj(u, f))))
      })
    }
  }

  def follow(username: String): Action[AnyContent] = Action.async { request =>
    withAuth(request) { (me, _) =>
      db.run((users.filter(_.username === username).result.headOption.flatMap {
        case None => DBIO.successful(notFound("profile"))
        case Some(u) =>
          follows.insertOrUpdate((me.id, u.id)).map(_ => Ok(Json.obj("profile" -> profileObj(u, true))))
      }).transactionally)
    }
  }

  def unfollow(username: String): Action[AnyContent] = Action.async { request =>
    withAuth(request) { (me, _) =>
      db.run((users.filter(_.username === username).result.headOption.flatMap {
        case None => DBIO.successful(notFound("profile"))
        case Some(u) =>
          follows.filter(f => f.followerId === me.id && f.followeeId === u.id).delete
            .map(_ => Ok(Json.obj("profile" -> profileObj(u, false))))
      }).transactionally)
    }
  }
}
