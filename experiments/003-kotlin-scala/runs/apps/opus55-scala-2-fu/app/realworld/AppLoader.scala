package realworld

import play.api.*
import play.api.ApplicationLoader.Context
import play.api.http.HttpErrorHandler
import play.api.mvc.*
import play.filters.cors.CORSComponents
import realworld.auth.Jwt
import realworld.controllers.*
import realworld.db.DatabaseProvider
import realworld.services.{ArticleService, UserService}
import router.Routes

import scala.concurrent.Future

/** Compile-time dependency injection: wires the database, services and controllers. */
class AppLoader extends ApplicationLoader:
  def load(context: Context): Application =
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new AppComponents(context).application

class AppComponents(context: Context) extends BuiltInComponentsFromContext(context) with CORSComponents:
  private val logger = Logger(getClass)
  private given scala.concurrent.ExecutionContext = executionContext

  private val database = DatabaseProvider.create(configuration.get[String]("realworld.db.path"))
  applicationLifecycle.addStopHook(() => Future.successful(database.close()))

  logDispatcherSizing()

  /** Logs the effective size of the dispatchers that serve HTTP requests and Futures. */
  private def logDispatcherSizing(): Unit =
    val conf = configuration.underlying
    def size(path: String): Int =
      val c = conf.getConfig(s"$path.fork-join-executor")
      org.apache.pekko.dispatch.ThreadPoolConfig.scaledPoolSize(
        c.getInt("parallelism-min"),
        c.getDouble("parallelism-factor"),
        c.getInt("parallelism-max")
      )
    logger.info(
      s"Available processors: ${Runtime.getRuntime.availableProcessors()}, " +
        s"pekko default-dispatcher parallelism: ${size("pekko.actor.default-dispatcher")}, " +
        s"internal-dispatcher parallelism: ${size("pekko.actor.internal-dispatcher")}"
    )

  private val jwt = Jwt(
    configuration.get[String]("realworld.jwt.secret"),
    configuration.get[Long]("realworld.jwt.ttl-seconds")
  )
  private val userService    = UserService(database)
  private val articleService = ArticleService(database)

  override lazy val httpErrorHandler: HttpErrorHandler = JsonErrorHandler()
  override def httpFilters: Seq[EssentialFilter]       = Seq(corsFilter)

  lazy val router: play.api.routing.Router = Routes(
    httpErrorHandler,
    UserController(controllerComponents, jwt, userService),
    ProfileController(controllerComponents, jwt, userService),
    ArticleController(controllerComponents, jwt, userService, articleService),
    CommentController(controllerComponents, jwt, userService, articleService),
    TagController(controllerComponents, jwt, userService, articleService)
  )

/** Renders framework-level errors (unknown routes, bad requests, crashes) in the RealWorld error format. */
class JsonErrorHandler extends HttpErrorHandler:
  private val logger = Logger(getClass)

  def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    val field = if statusCode == 404 then "route" else "request"
    val msg   = if statusCode == 404 then "not found" else Option(message).filter(_.nonEmpty).getOrElse("is invalid")
    Future.successful(Results.Status(statusCode)(ApiError.body(Seq(field -> msg))))

  def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    exception match
      case e: ApiError => Future.successful(e.toResult)
      case e =>
        logger.error(s"Error handling ${request.method} ${request.uri}", e)
        Future.successful(Results.InternalServerError(ApiError.body(Seq("server" -> "internal error"))))
