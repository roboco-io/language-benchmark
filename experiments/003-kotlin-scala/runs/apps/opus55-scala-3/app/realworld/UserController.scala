package realworld

import play.api.libs.json.*
import play.api.mvc.*

import scala.concurrent.{ExecutionContext, Future}

final class UserController(cc: ControllerComponents, jwt: Jwt, repo: Repo)(using ExecutionContext) extends ApiController(cc, jwt):

  private def respond(u: User, status: Status = Ok): Result = status(Render.user(u, jwt.issue(u.id, u.username)))

  private def conflict(fields: Seq[String]): Result = errors(Conflict, fields.map(_ -> "has already been taken")*)

  def register: Action[AnyContent] = Action.async { req =>
    bodyObject(req, "user").fold(done, o =>
      val (username, email, password) = (str(o, "username"), str(o, "email"), str(o, "password"))
      val missing = Seq("username" -> username, "email" -> email, "password" -> password).collect { case (k, None) => k }
      if missing.nonEmpty then done(blank(missing*))
      else
        // Hash outside the single-writer transaction: PBKDF2 is CPU heavy.
        Future(Passwords.hash(password.get)).flatMap(hash =>
          repo.createUser(username.get.trim, email.get.trim, hash).map(_.fold(conflict, respond(_, Created)))))
  }

  def login: Action[AnyContent] = Action.async { req =>
    bodyObject(req, "user").fold(done, o =>
      val (email, password) = (str(o, "email"), str(o, "password"))
      val missing = Seq("email" -> email, "password" -> password).collect { case (k, None) => k }
      if missing.nonEmpty then done(blank(missing*))
      else
        repo.findUserByEmail(email.get.trim).map {
          case Some(u) if Passwords.verify(password.get, u.passwordHash) => respond(u)
          case _ => errors(Unauthorized, "credentials" -> "invalid")
        })
  }

  def current: Action[AnyContent] = authed { (_, id) =>
    repo.findUser(id).map(_.fold(unauthorized("is invalid"))(respond(_)))
  }

  def update: Action[AnyContent] = authed { (req, id) =>
    bodyObject(req, "user").fold(done, o =>
      // username/email/password may be omitted but not set to null or blank.
      def required(field: String): Either[(String, String), Option[String]] = o \ field match
        case JsDefined(JsString(s)) if s.trim.nonEmpty => Right(Some(s.trim))
        case _: JsUndefined => Right(None)
        case _ => Left(field -> "can't be blank")
      // bio/image may be cleared with null or an empty string.
      def nullable(field: String): Option[Option[String]] = o \ field match
        case JsDefined(JsString(s)) => Some(Option(s).filter(_.trim.nonEmpty))
        case JsDefined(_) => Some(None)
        case _ => None
      val username = required("username")
      val email = required("email")
      val password = required("password").flatMap {
        case Some(p) if p.length < 8 => Left("password" -> "is too short (minimum is 8 characters)")
        case other => Right(other)
      }
      val problems = Seq(username, email, password).collect { case Left(e) => e }
      if problems.nonEmpty then done(errors(UnprocessableEntity, problems*))
      else
        val hash = password.toOption.flatten.map(Passwords.hash)
        val patch = UserPatch(username.toOption.flatten, email.toOption.flatten, hash, nullable("bio"), nullable("image"))
        repo.updateUser(id, patch).map(_.fold(conflict, respond(_))))
  }

final class ProfileController(cc: ControllerComponents, jwt: Jwt, repo: Repo)(using ExecutionContext) extends ApiController(cc, jwt):

  private def render(p: Option[Profile]): Result =
    p.fold(notFound("profile"))(p => Ok(Json.obj("profile" -> Render.profile(p))))

  def get(username: String): Action[AnyContent] = maybeAuthed { (_, viewer) =>
    repo.profile(username, viewer).map(render)
  }

  def follow(username: String): Action[AnyContent] = authed { (_, id) =>
    repo.setFollow(id, username, follow = true).map(render)
  }

  def unfollow(username: String): Action[AnyContent] = authed { (_, id) =>
    repo.setFollow(id, username, follow = false).map(render)
  }

final class TagController(cc: ControllerComponents, jwt: Jwt, repo: Repo)(using ExecutionContext) extends ApiController(cc, jwt):
  def list: Action[AnyContent] = Action.async(repo.tags().map(t => Ok(Json.obj("tags" -> t))))
