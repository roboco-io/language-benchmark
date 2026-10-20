package controllers

import models.Tables.*
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.*
import play.api.mvc.*
import services.Security
import slick.jdbc.SQLiteProfile.api.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class TagController @Inject() (cc: ControllerComponents, dbp: DatabaseConfigProvider, security: Security)(using ExecutionContext)
    extends ApiSupport(cc, dbp, security) {

  def list: Action[AnyContent] = Action.async {
    db.run(articleTags.map(_.name).distinct.sorted.result).map(tags => Ok(Json.obj("tags" -> tags)))
  }
}
