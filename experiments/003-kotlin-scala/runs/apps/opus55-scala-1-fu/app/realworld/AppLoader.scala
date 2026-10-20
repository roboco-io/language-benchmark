package realworld

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import play.api.*
import play.api.ApplicationLoader.Context
import play.api.http.HttpErrorHandler
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.filters.cors.CORSComponents
import realworld.auth.{Jwt, Passwords}
import realworld.controllers.*
import realworld.db.Db
import realworld.http.{Authenticator, ErrorHandler}
import realworld.services.*

import scala.concurrent.{ExecutionContext, Future}

/** Compile-time wiring. Also sizes the Pekko default dispatcher (which runs both the Pekko HTTP
  * server and the application code) and the Pekko internal dispatcher (Pekko IO) to exactly the
  * number of available CPU cores.
  */
final class AppLoader extends ApplicationLoader {
  override def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    val cores = Runtime.getRuntime.availableProcessors()
    val pool = s"""{
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = $cores
          parallelism-factor = 1.0
          parallelism-max = $cores
        }
      }"""
    val threads = Configuration(ConfigFactory.parseString(s"""
      pekko.actor.default-dispatcher $pool
      pekko.actor.internal-dispatcher $pool
    """))
    val ctx = context.copy(initialConfiguration = threads.withFallback(context.initialConfiguration))
    LoggerFactory.getLogger(getClass).info(s"Available CPU cores: $cores (dispatcher and DB reader pool sized to match)")
    new AppComponents(ctx, cores).application
  }
}

final class AppComponents(context: Context, cores: Int) extends BuiltInComponentsFromContext(context) with CORSComponents {
  private given ExecutionContext = executionContext

  override lazy val httpErrorHandler: HttpErrorHandler = new ErrorHandler
  override def httpFilters: Seq[EssentialFilter] = Seq(corsFilter)

  lazy val db: Db = {
    val d = new Db(configuration.get[String]("realworld.db.path"), cores)
    d.init()
    applicationLifecycle.addStopHook(() => Future.successful(d.close()))
    d
  }

  lazy val jwt = new Jwt(
    configuration.get[String]("realworld.jwt.secret"),
    configuration.get[Long]("realworld.jwt.ttl-hours") * 3600
  )
  lazy val passwords = new Passwords(configuration.get[Int]("realworld.password.iterations"))

  lazy val userService = new UserService(db, passwords)
  lazy val profileService = new ProfileService(db)
  lazy val articleService = new ArticleService(db)
  lazy val commentService = new CommentService(db, articleService)
  lazy val authenticator = new Authenticator(jwt, userService)

  lazy val router: Router = new _root_.router.Routes(
    httpErrorHandler,
    new UserController(controllerComponents, authenticator, userService, jwt),
    new ProfileController(controllerComponents, authenticator, profileService),
    new ArticleController(controllerComponents, authenticator, articleService),
    new CommentController(controllerComponents, authenticator, commentService),
    new TagController(controllerComponents, authenticator, articleService)
  )

  // Create the schema eagerly at startup rather than on the first request.
  db
}
