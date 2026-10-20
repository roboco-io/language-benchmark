package realworld.db

import com.typesafe.config.{Config, ConfigFactory}
import play.api.Logger
import slick.jdbc.SQLiteProfile.api.*

import java.nio.file.{Files, Paths}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Builds the Slick database for the SQLite file and creates the schema if it is missing. */
object DatabaseProvider:
  private val logger = Logger(getClass)

  def create(path: String)(using ExecutionContext): Database =
    val file = Paths.get(path).toAbsolutePath
    Option(file.getParent).foreach(Files.createDirectories(_))

    val cores = Runtime.getRuntime.availableProcessors()
    val config: Config = ConfigFactory.parseMap(
      Map[String, Any](
        "url"             -> s"jdbc:sqlite:$file",
        "driver"          -> "org.sqlite.JDBC",
        "connectionPool"  -> "HikariCP",
        "numThreads"      -> cores,
        "minConnections"  -> cores,
        "maxConnections"  -> cores,
        "queueSize"       -> 10000,
        "registerMbeans"  -> false,
        // Per-connection SQLite settings, applied by the sqlite-jdbc driver when it opens a connection.
        "properties" -> Map(
          "journal_mode"     -> "WAL",
          "busy_timeout"     -> "5000",
          "synchronous"      -> "NORMAL",
          "foreign_keys"     -> "true",
          // BEGIN IMMEDIATE takes the write lock up front, so read-then-write
          // transactions wait on busy_timeout instead of failing with SQLITE_BUSY.
          "transaction_mode" -> "IMMEDIATE"
        ).asJava
      ).asJava
    )
    val db = Database.forConfig("", config)

    val init = DBIO.seq(Tables.schemaStatements.map(s => sqlu"#$s")*)
    Await.result(db.run(init), 60.seconds)

    val check = for
      mode    <- sql"PRAGMA journal_mode".as[String].head
      timeout <- sql"PRAGMA busy_timeout".as[Int].head
    yield (mode, timeout)
    val (mode, timeout) = Await.result(db.run(check), 30.seconds)
    logger.info(s"SQLite database at $file (journal_mode=$mode, busy_timeout=${timeout}ms, pool=$cores)")
    db
