package realworld

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import slick.jdbc.SQLiteProfile.api.*
import slick.util.AsyncExecutor

import java.nio.file.{Files, Paths}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** SQLite access through Slick.
  *
  * SQLite allows many concurrent readers but only one writer. Writes therefore go
  * through a dedicated single-connection pool, so they are serialized inside the JVM
  * and never fight over the database lock. Reads use a pool sized to the CPU count.
  * Every connection runs in WAL mode with a 5000 ms busy timeout.
  */
final class Db(path: String, cores: Int):
  Option(Paths.get(path).toAbsolutePath.getParent).foreach(Files.createDirectories(_))

  private def dataSource(name: String, size: Int): HikariDataSource =
    val cfg = HikariConfig()
    cfg.setPoolName(name)
    cfg.setDriverClassName("org.sqlite.JDBC")
    cfg.setJdbcUrl(s"jdbc:sqlite:$path")
    cfg.setMaximumPoolSize(size)
    cfg.setMinimumIdle(size)
    cfg.addDataSourceProperty("journal_mode", "WAL")
    cfg.addDataSourceProperty("busy_timeout", "5000")
    cfg.addDataSourceProperty("foreign_keys", "true")
    cfg.addDataSourceProperty("synchronous", "NORMAL")
    HikariDataSource(cfg)

  private def database(name: String, size: Int): Database =
    Database.forDataSource(
      dataSource(name, size),
      Some(size),
      AsyncExecutor(name, minThreads = size, maxThreads = size, queueSize = 10000, maxConnections = size)
    )

  val writer: Database = database("sqlite-writer", 1)
  val reader: Database = database("sqlite-reader", cores)

  def write[T](action: DBIO[T]) = writer.run(action.transactionally)
  def read[T](action: DBIO[T]) = reader.run(action)

  /** Creates the schema if it does not exist yet. Blocks until done. */
  def migrate(): Unit =
    Await.result(writer.run(DBIO.sequence(Schema.statements.map(s => sqlu"#$s"))), 30.seconds)

  def pragma(name: String): String =
    Await.result(reader.run(sql"PRAGMA #$name".as[String].head), 10.seconds)

  def close(): Unit =
    writer.close()
    reader.close()

object Schema:
  val statements: Seq[String] = Seq(
    """CREATE TABLE IF NOT EXISTS users (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  username TEXT NOT NULL UNIQUE,
      |  email TEXT NOT NULL UNIQUE,
      |  password_hash TEXT NOT NULL,
      |  bio TEXT,
      |  image TEXT
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS follows (
      |  follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  PRIMARY KEY (follower_id, followee_id)
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS follows_followee ON follows(followee_id)",
    """CREATE TABLE IF NOT EXISTS articles (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  slug TEXT NOT NULL UNIQUE,
      |  title TEXT NOT NULL,
      |  description TEXT NOT NULL,
      |  body TEXT NOT NULL,
      |  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  created_at INTEGER NOT NULL,
      |  updated_at INTEGER NOT NULL
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS articles_author ON articles(author_id)",
    """CREATE TABLE IF NOT EXISTS tags (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  name TEXT NOT NULL UNIQUE
      |)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS article_tags (
      |  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      |  tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      |  position INTEGER NOT NULL,
      |  PRIMARY KEY (article_id, tag_id)
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS article_tags_tag ON article_tags(tag_id)",
    """CREATE TABLE IF NOT EXISTS favorites (
      |  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      |  PRIMARY KEY (user_id, article_id)
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS favorites_article ON favorites(article_id)",
    """CREATE TABLE IF NOT EXISTS comments (
      |  id INTEGER PRIMARY KEY AUTOINCREMENT,
      |  article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      |  author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      |  body TEXT NOT NULL,
      |  created_at INTEGER NOT NULL,
      |  updated_at INTEGER NOT NULL
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS comments_article ON comments(article_id)"
  )
