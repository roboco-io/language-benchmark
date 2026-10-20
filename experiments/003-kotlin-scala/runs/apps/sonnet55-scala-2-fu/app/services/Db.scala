package services

import javax.inject.{Inject, Singleton}

import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import slick.jdbc.SQLiteProfile.api.*

import scala.concurrent.Future

/** Owns the Slick database (HikariCP over SQLite) and creates the schema on startup. */
@Singleton
class Db @Inject() (config: Configuration, lifecycle: ApplicationLifecycle):
  private val cores = Runtime.getRuntime.availableProcessors
  private val path = config.get[String]("realworld.db.path")

  // Every pooled connection runs in WAL mode with a 5000 ms busy timeout.
  // IMMEDIATE transactions take the write lock up front so that writers queue on
  // busy_timeout instead of failing with SQLITE_BUSY on a read-to-write upgrade.
  val db: slick.jdbc.SQLiteProfile.backend.Database =
    val cfg = ConfigFactory.parseString(s"""
      connectionPool = HikariCP
      driver = org.sqlite.JDBC
      url = "jdbc:sqlite:${path.replace("\\", "/")}"
      numThreads = $cores
      maxConnections = $cores
      minConnections = $cores
      queueSize = 2000
      properties {
        journal_mode = WAL
        busy_timeout = 5000
        synchronous = NORMAL
        foreign_keys = true
        transaction_mode = IMMEDIATE
      }
    """)
    Database.forConfig("", cfg)

  private val schema = Seq(
    """CREATE TABLE IF NOT EXISTS users (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         email TEXT NOT NULL UNIQUE,
         username TEXT NOT NULL UNIQUE,
         password_hash TEXT NOT NULL,
         bio TEXT,
         image TEXT)""",
    """CREATE TABLE IF NOT EXISTS articles (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         slug TEXT NOT NULL UNIQUE,
         title TEXT NOT NULL,
         description TEXT NOT NULL,
         body TEXT NOT NULL,
         author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL)""",
    "CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id)",
    "CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at DESC, id DESC)",
    """CREATE TABLE IF NOT EXISTS article_tags (
         article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
         tag TEXT NOT NULL,
         PRIMARY KEY (article_id, tag))""",
    "CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag)",
    """CREATE TABLE IF NOT EXISTS favorites (
         user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
         article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
         PRIMARY KEY (user_id, article_id))""",
    "CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id)",
    """CREATE TABLE IF NOT EXISTS follows (
         follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
         followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
         PRIMARY KEY (follower_id, followee_id))""",
    """CREATE TABLE IF NOT EXISTS comments (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
         author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
         body TEXT NOT NULL,
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL)""",
    "CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)"
  )

  // Creating the schema at construction time (on the startup thread) guarantees that
  // an empty database is ready before the first request is served.
  scala.concurrent.Await.result(
    db.run(DBIO.seq(schema.map(s => sqlu"#$s")*)),
    scala.concurrent.duration.Duration(30, "seconds")
  )

  lifecycle.addStopHook(() => Future.successful(db.close()))
