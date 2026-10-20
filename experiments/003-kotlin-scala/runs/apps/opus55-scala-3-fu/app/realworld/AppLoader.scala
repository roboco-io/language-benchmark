package realworld

import play.api.*
import play.api.ApplicationLoader.Context
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.filters.cors.CORSComponents
import router.Routes

import scala.concurrent.Future

/** Compile-time wiring of the application (no runtime DI container). */
final class AppLoader extends ApplicationLoader:
  def load(context: Context): Application =
    LoggerConfigurator(context.environment.classLoader).foreach(_.configure(context.environment, context.initialConfiguration, Map.empty))
    AppComponents(context).application

final class AppComponents(context: Context) extends BuiltInComponentsFromContext(context) with CORSComponents:
  private val log = Logger("realworld")
  private given scala.concurrent.ExecutionContext = executionContext

  private val cores = Runtime.getRuntime.availableProcessors()
  private val cfg = configuration.underlying

  val db = Db(cfg.getString("realworld.db.path"), cores)
  db.migrate()
  log.info(s"SQLite ${cfg.getString("realworld.db.path")}: journal_mode=${db.pragma("journal_mode")}, busy_timeout=${db.pragma("busy_timeout")}, cores=$cores")
  applicationLifecycle.addStopHook(() => Future.successful(db.close()))

  val jwt = Jwt(cfg.getString("realworld.jwt.secret"), cfg.getLong("realworld.jwt.ttl-days") * 86400L)
  val repo = Repo(db)

  override def httpFilters: Seq[EssentialFilter] = Seq(corsFilter)

  lazy val router: Router = Routes(
    httpErrorHandler,
    UserController(controllerComponents, jwt, repo),
    ProfileController(controllerComponents, jwt, repo),
    ArticleController(controllerComponents, jwt, repo),
    CommentController(controllerComponents, jwt, repo),
    TagController(controllerComponents, jwt, repo)
  )
