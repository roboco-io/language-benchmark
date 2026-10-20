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
class UserController @Inject() (cc: ControllerComponents, dbp: DatabaseConfigProvider, security: Security)(using ExecutionContext)
    extends ApiSupport(cc, dbp, security) {

  private val MinPassword = 8

  def register: Action[JsValue] = Action.async(parse.json) { request =>
    val u = request.body \ "user"
    val username = (u \ "username").asOpt[String].map(_.trim)
    val email = (u \ "email").asOpt[String].map(_.trim)
    val password = (u \ "password").asOpt[String]
    val errs = Seq(
      if (blank(username)) Some("username" -> "can't be blank") else None,
      if (blank(email)) Some("email" -> "can't be blank") else None,
      if (blank(password)) Some("password" -> "can't be blank")
      else if (password.exists(_.length < MinPassword)) Some("password" -> "is too short (minimum is 8 characters)")
      else None
    ).flatten
    if (errs.nonEmpty) Future.successful(invalid(errs*))
    else {
      val hash = security.hashPassword(password.get)
      val action = (for {
        nameTaken <- users.filter(_.username === username.get).exists.result
        mailTaken <- users.filter(_.email === email.get).exists.result
        res <-
          if (nameTaken || mailTaken)
            DBIO.successful(Left(Seq(
              if (nameTaken) Some("username" -> "has already been taken") else None,
              if (mailTaken) Some("email" -> "has already been taken") else None
            ).flatten))
          else
            (users.map(r => (r.email, r.username, r.passwordHash)) returning users.map(_.id) +=
              ((email.get, username.get, hash))).map(Right(_))
      } yield res).transactionally
      db.run(action).map {
        case Left(es) => errors(409, es*)
        case Right(id) =>
          Created(userJson(UserRow(id, email.get, username.get, hash, None, None), security.issueToken(id)))
      }
    }
  }

  def login: Action[JsValue] = Action.async(parse.json) { request =>
    val u = request.body \ "user"
    val email = (u \ "email").asOpt[String].map(_.trim)
    val password = (u \ "password").asOpt[String]
    val errs = Seq(
      if (blank(email)) Some("email" -> "can't be blank") else None,
      if (blank(password)) Some("password" -> "can't be blank") else None
    ).flatten
    if (errs.nonEmpty) Future.successful(invalid(errs*))
    else
      db.run(users.filter(_.email === email.get).result.headOption).map {
        case Some(user) if security.checkPassword(password.get, user.passwordHash) =>
          Ok(userJson(user, security.issueToken(user.id)))
        case _ => errors(401, "credentials" -> "invalid")
      }
  }

  def current: Action[AnyContent] = Action.async { request =>
    withAuth(request)((user, token) => Future.successful(Ok(userJson(user, token))))
  }

  def update: Action[JsValue] = Action.async(parse.json) { request =>
    withAuth(request) { (me, token) =>
      val u = request.body \ "user"
      // A present-but-null or blank required field is rejected.
      def required(name: String): Either[(String, String), Option[String]] = u \ name match {
        case JsDefined(JsString(s)) if s.trim.nonEmpty => Right(Some(s.trim))
        case JsDefined(_) => Left(name -> "can't be blank")
        case _ => Right(None)
      }
      def optional(name: String): Option[Option[String]] = u \ name match {
        case JsDefined(JsString(s)) => Some(Some(s).filter(_.nonEmpty))
        case JsDefined(JsNull) => Some(None)
        case _ => None
      }
      val password: Either[(String, String), Option[String]] = u \ "password" match {
        case JsDefined(JsString(s)) if s.isEmpty => Left("password" -> "can't be blank")
        case JsDefined(JsString(s)) if s.length < MinPassword => Left("password" -> "is too short (minimum is 8 characters)")
        case JsDefined(JsString(s)) => Right(Some(s))
        case JsDefined(_) => Left("password" -> "can't be blank")
        case _ => Right(None)
      }
      val checks = Seq(required("username"), required("email"), password)
      val errs = checks.collect { case Left(e) => e }
      if (errs.nonEmpty) Future.successful(invalid(errs*))
      else {
        val Seq(Right(newName), Right(newMail), Right(newPass)) = checks: @unchecked
        val action = (for {
          nameTaken <- newName.filter(_ != me.username) match {
            case Some(n) => users.filter(r => r.username === n && r.id =!= me.id).exists.result
            case None => DBIO.successful(false)
          }
          mailTaken <- newMail.filter(_ != me.email) match {
            case Some(m) => users.filter(r => r.email === m && r.id =!= me.id).exists.result
            case None => DBIO.successful(false)
          }
          res <-
            if (nameTaken || mailTaken)
              DBIO.successful(Left(Seq(
                if (nameTaken) Some("username" -> "has already been taken") else None,
                if (mailTaken) Some("email" -> "has already been taken") else None
              ).flatten))
            else {
              val updated = me.copy(
                username = newName.getOrElse(me.username),
                email = newMail.getOrElse(me.email),
                passwordHash = newPass.map(security.hashPassword).getOrElse(me.passwordHash),
                bio = optional("bio").getOrElse(me.bio),
                image = optional("image").getOrElse(me.image)
              )
              users.filter(_.id === me.id).update(updated).map(_ => Right(updated))
            }
        } yield res).transactionally
        db.run(action).map {
          case Left(es) => errors(409, es*)
          case Right(updated) => Ok(userJson(updated, token))
        }
      }
    }
  }
}
