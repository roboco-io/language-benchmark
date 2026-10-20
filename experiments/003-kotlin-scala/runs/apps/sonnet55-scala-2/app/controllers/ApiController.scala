package controllers

import javax.inject.{Inject, Singleton}

import play.api.Configuration
import play.api.libs.json.*
import play.api.mvc.*
import services.*
import auth.Security

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiController @Inject() (cc: ControllerComponents, repo: Repo, config: Configuration)(using ec: ExecutionContext)
    extends AbstractController(cc):

  private val secret = config.get[String]("realworld.jwt.secret")
  private val ttl = config.get[Long]("realworld.jwt.ttl-seconds")

  // ------------------------------------------------------------- helpers

  private def fail[A](status: Int, field: String, msg: String): Future[A] =
    Future.failed(ApiException(status, field, msg))

  private def errorJson(e: ApiException): Result =
    Status(e.status)(Json.obj("errors" -> Json.toJson(e.errors)))

  /** Runs `body` and converts an ApiException into the RealWorld error envelope. */
  private def run(body: => Future[Result]): Future[Result] =
    try body.recover { case e: ApiException => errorJson(e) }
    catch case e: ApiException => Future.successful(errorJson(e))

  private def tokenOf(req: RequestHeader): Option[String] =
    req.headers.get("Authorization").map(_.trim).collect {
      case h if h.regionMatches(true, 0, "Token ", 0, 6) => h.substring(6).trim
      case h if h.regionMatches(true, 0, "Bearer ", 0, 7) => h.substring(7).trim
    }

  /** Present-and-valid token gives Some(user id); absent gives None; invalid fails with 401. */
  private def optionalUser(req: RequestHeader): Future[Option[Long]] =
    req.headers.get("Authorization") match
      case None => Future.successful(None)
      case Some(_) =>
        tokenOf(req).flatMap(Security.verifyToken(secret, _)) match
          case Some(id) => Future.successful(Some(id))
          case None => fail(401, "token", "is invalid")

  private def requiredUser(req: RequestHeader): Future[UserRow] =
    if req.headers.get("Authorization").isEmpty then fail(401, "token", "is missing")
    else
      optionalUser(req).flatMap {
        case Some(id) => repo.findUserById(id).flatMap {
          case Some(u) => Future.successful(u)
          case None => fail(401, "token", "is invalid")
        }
        case None => fail(401, "token", "is missing")
      }

  private def now[A](a: A): Future[A] = Future.successful(a)

  private def wrapper(req: Request[JsValue], key: String): Map[String, JsValue] =
    (req.body \ key).asOpt[JsObject].map(_.value.toMap).getOrElse(Map.empty)

  private def hasWrapper(req: Request[JsValue], key: String): Boolean = (req.body \ key).asOpt[JsObject].isDefined

  private def str(m: Map[String, JsValue], k: String): Option[String] = m.get(k).flatMap(_.asOpt[String])

  private def blank(s: Option[String]): Boolean = s.forall(_.trim.isEmpty)

  private def validate(checks: (String, Boolean)*): Unit =
    val errs = checks.collect { case (f, true) => f -> List("can't be blank") }.toMap
    if errs.nonEmpty then throw ApiException(422, errs)

  private val MinPassword = 8
  private val EmailRe = """^[^@\s]+@[^@\s]+$""".r

  private def checkPassword(p: String): Unit =
    if p.length < MinPassword then throw ApiException(422, "password", s"is too short (minimum is $MinPassword characters)")

  private def checkEmail(e: String): Unit =
    if EmailRe.findFirstIn(e).isEmpty then throw ApiException(422, "email", "is invalid")

  // --------------------------------------------------------------- JSON

  private def opt(s: Option[String]): JsValue = s.fold[JsValue](JsNull)(JsString(_))

  private def userJson(u: UserRow): JsObject =
    Json.obj("user" -> Json.obj(
      "email" -> u.email, "token" -> Security.issueToken(secret, u.id, ttl), "username" -> u.username,
      "bio" -> opt(u.bio), "image" -> opt(u.image)))

  private def profileObj(p: Profile): JsObject =
    Json.obj("username" -> p.username, "bio" -> opt(p.bio), "image" -> opt(p.image), "following" -> p.following)

  private def articleObj(a: ArticleView, withBody: Boolean = true): JsObject =
    val base = Json.obj(
      "slug" -> a.slug, "title" -> a.title, "description" -> a.description,
      "tagList" -> a.tagList, "createdAt" -> a.createdAt, "updatedAt" -> a.updatedAt,
      "favorited" -> a.favorited, "favoritesCount" -> a.favoritesCount, "author" -> profileObj(a.author))
    if withBody then base + ("body" -> JsString(a.body)) else base

  private def commentObj(c: CommentView): JsObject =
    Json.obj("id" -> c.id, "createdAt" -> c.createdAt, "updatedAt" -> c.updatedAt, "body" -> c.body,
      "author" -> profileObj(c.author))

  // --------------------------------------------------------------- users

  def register: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    run {
      val u = wrapper(req, "user")
      val username = str(u, "username").map(_.trim)
      val email = str(u, "email").map(_.trim)
      val password = str(u, "password")
      validate("username" -> blank(username), "email" -> blank(email), "password" -> blank(password))
      checkEmail(email.get)
      checkPassword(password.get)
      repo.createUser(email.get, username.get, Security.hashPassword(password.get))
        .map(user => Created(userJson(user)))
    }
  }

  def login: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    run {
      val u = wrapper(req, "user")
      val email = str(u, "email").map(_.trim)
      val password = str(u, "password")
      validate("email" -> blank(email), "password" -> blank(password))
      repo.findUserByEmail(email.get).flatMap {
        case Some(user) if Security.verifyPassword(password.get, user.passwordHash) => now(Ok(userJson(user)))
        case _ => fail(401, "credentials", "invalid")
      }
    }
  }

  def currentUser: Action[AnyContent] = Action.async { req =>
    run(requiredUser(req).map(u => Ok(userJson(u))))
  }

  def updateUser: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    run {
      requiredUser(req).flatMap { current =>
        val u = wrapper(req, "user")
        def required(k: String, old: String): String = u.get(k) match
          case None => old
          case Some(JsString(s)) if s.trim.nonEmpty => s.trim
          case _ => throw ApiException(422, k, "can't be blank")
        def optional(k: String, old: Option[String]): Option[String] = u.get(k) match
          case None => old
          case Some(JsNull) => None
          case Some(JsString(s)) => Some(s).filter(_.nonEmpty)
          case _ => throw ApiException(422, k, "is invalid")
        val email = required("email", current.email)
        checkEmail(email)
        val password = u.get("password") match
          case None => current.passwordHash
          case Some(JsString(p)) if p.nonEmpty => checkPassword(p); Security.hashPassword(p)
          case _ => throw ApiException(422, "password", "can't be blank")
        val updated = current.copy(
          email = email, username = required("username", current.username), passwordHash = password,
          bio = optional("bio", current.bio), image = optional("image", current.image))
        repo.updateUser(updated).map(x => Ok(userJson(x)))
      }
    }
  }

  // ------------------------------------------------------------ profiles

  private def profileResult(username: String, viewer: Option[Long]): Future[Result] =
    repo.profileOf(username, viewer).flatMap {
      case Some(p) => now(Ok(Json.obj("profile" -> profileObj(p))))
      case None => fail(404, "profile", "not found")
    }

  def getProfile(username: String): Action[AnyContent] = Action.async { req =>
    run(optionalUser(req).flatMap(profileResult(username, _)))
  }

  private def setFollow(username: String, on: Boolean): Action[AnyContent] = Action.async { req =>
    run {
      requiredUser(req).flatMap { me =>
        repo.findUserByUsername(username).flatMap {
          case None => fail(404, "profile", "not found")
          case Some(target) =>
            repo.setFollow(me.id, target.id, on).flatMap(_ => profileResult(username, Some(me.id)))
        }
      }
    }
  }

  def follow(username: String): Action[AnyContent] = setFollow(username, true)
  def unfollow(username: String): Action[AnyContent] = setFollow(username, false)

  // ------------------------------------------------------------ articles

  private def articleResult(slug: String, viewer: Option[Long], status: Status = Ok): Future[Result] =
    repo.findArticle(slug, viewer).flatMap {
      case Some(a) => now(status(Json.obj("article" -> articleObj(a))))
      case None => fail(404, "article", "not found")
    }

  private def intParam(req: RequestHeader, name: String, default: Int, min: Int, max: Int): Int =
    req.getQueryString(name).flatMap(_.toIntOption).getOrElse(default).max(min).min(max)

  private def listResult(req: RequestHeader, viewer: Option[Long], feedOf: Option[Long]): Future[Result] =
    val limit = intParam(req, "limit", 20, 0, 1000)
    val offset = intParam(req, "offset", 0, 0, Int.MaxValue)
    def q(n: String) = req.getQueryString(n).filter(_.nonEmpty)
    repo.listArticles(q("tag"), q("author"), q("favorited"), feedOf, viewer, limit, offset).map { (arts, total) =>
      Ok(Json.obj("articles" -> arts.map(articleObj(_, withBody = false)), "articlesCount" -> total))
    }

  def listArticles: Action[AnyContent] = Action.async { req =>
    run(optionalUser(req).flatMap(v => listResult(req, v, None)))
  }

  def feed: Action[AnyContent] = Action.async { req =>
    run(requiredUser(req).flatMap(me => listResult(req, Some(me.id), Some(me.id))))
  }

  def getArticle(slug: String): Action[AnyContent] = Action.async { req =>
    run(optionalUser(req).flatMap(articleResult(slug, _)))
  }

  private def tagsOf(m: Map[String, JsValue]): Option[Seq[String]] = m.get("tagList") match
    case None => None
    case Some(JsArray(items)) if items.forall(_.isInstanceOf[JsString]) =>
      Some(items.collect { case JsString(s) => s.trim }.filter(_.nonEmpty).toSeq)
    case _ => throw ApiException(422, "tagList", "is invalid")

  def createArticle: Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    run {
      requiredUser(req).flatMap { me =>
        val a = wrapper(req, "article")
        val (title, desc, body) = (str(a, "title"), str(a, "description"), str(a, "body"))
        validate("title" -> blank(title), "description" -> blank(desc), "body" -> blank(body))
        val tags = tagsOf(a).getOrElse(Seq.empty)
        repo.createArticle(me.id, title.get.trim, desc.get, body.get, tags)
          .flatMap(slug => articleResult(slug, Some(me.id), Created))
      }
    }
  }

  def updateArticle(slug: String): Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    run {
      requiredUser(req).flatMap { me =>
        repo.findArticle(slug, Some(me.id)).flatMap {
          case None => fail(404, "article", "not found")
          case Some(a) =>
            if a.author.username != me.username then fail(403, "article", "forbidden")
            else
              val m = wrapper(req, "article")
              def field(k: String, old: String): String = m.get(k) match
                case None => old
                case Some(JsString(s)) if s.trim.nonEmpty => s
                case _ => throw ApiException(422, k, "can't be blank")
              val title = field("title", a.title).trim
              val tags = tagsOf(m)
              repo.updateArticle(a, title, field("description", a.description), field("body", a.body), tags)
                .flatMap(newSlug => articleResult(newSlug, Some(me.id)))
        }
      }
    }
  }

  def deleteArticle(slug: String): Action[AnyContent] = Action.async { req =>
    run {
      requiredUser(req).flatMap { me =>
        repo.articleAuthor(slug).flatMap {
          case None => fail(404, "article", "not found")
          case Some((_, authorId)) if authorId != me.id => fail(403, "article", "forbidden")
          case Some((id, _)) => repo.deleteArticle(id).map(_ => NoContent)
        }
      }
    }
  }

  private def setFavorite(slug: String, on: Boolean): Action[AnyContent] = Action.async { req =>
    run {
      requiredUser(req).flatMap { me =>
        repo.articleAuthor(slug).flatMap {
          case None => fail(404, "article", "not found")
          case Some((id, _)) =>
            repo.setFavorite(me.id, id, on).flatMap(_ => articleResult(slug, Some(me.id)))
        }
      }
    }
  }

  def favorite(slug: String): Action[AnyContent] = setFavorite(slug, true)
  def unfavorite(slug: String): Action[AnyContent] = setFavorite(slug, false)

  // ------------------------------------------------------------ comments

  def listComments(slug: String): Action[AnyContent] = Action.async { req =>
    run {
      optionalUser(req).flatMap { viewer =>
        repo.articleAuthor(slug).flatMap {
          case None => fail(404, "article", "not found")
          case Some((id, _)) =>
            repo.listComments(id, viewer).map(cs => Ok(Json.obj("comments" -> cs.map(commentObj))))
        }
      }
    }
  }

  def addComment(slug: String): Action[JsValue] = Action.async(parse.tolerantJson) { req =>
    run {
      requiredUser(req).flatMap { me =>
        repo.articleAuthor(slug).flatMap {
          case None => fail(404, "article", "not found")
          case Some((id, _)) =>
            val body = str(wrapper(req, "comment"), "body")
            validate("body" -> blank(body))
            repo.addComment(id, me.id, body.get).map(c => Created(Json.obj("comment" -> commentObj(c))))
        }
      }
    }
  }

  def deleteComment(slug: String, id: Long): Action[AnyContent] = Action.async { req =>
    run {
      requiredUser(req).flatMap { me =>
        repo.articleAuthor(slug).flatMap {
          case None => fail(404, "article", "not found")
          case Some((articleId, _)) =>
            repo.findComment(id, articleId).flatMap {
              case None => fail(404, "comment", "not found")
              case Some(authorId) if authorId != me.id => fail(403, "comment", "forbidden")
              case Some(_) => repo.deleteComment(id).map(_ => NoContent)
            }
        }
      }
    }
  }

  // ---------------------------------------------------------------- tags

  def tags: Action[AnyContent] = Action.async {
    run(repo.allTags().map(ts => Ok(Json.obj("tags" -> ts))))
  }
