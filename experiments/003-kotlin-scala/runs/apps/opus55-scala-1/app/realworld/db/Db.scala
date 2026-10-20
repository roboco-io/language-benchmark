package realworld.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.slf4j.LoggerFactory
import slick.jdbc.SQLiteProfile.api.*
import slick.util.AsyncExecutor

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/** SQLite access through Slick.
  *
  * SQLite allows only one writer at a time, so writes go through a dedicated single-connection
  * database (no lock contention between our own connections), while reads use a pool sized to
  * the CPU core count. Every connection runs in WAL mode with `busy_timeout = 5000` ms.
  */
final class Db(path: String, readerThreads: Int) {
  private val log = LoggerFactory.getLogger(getClass)

  private val file: Path = Paths.get(path).toAbsolutePath
  Option(file.getParent).foreach(p => Files.createDirectories(p))

  private def dataSource(name: String, size: Int): HikariDataSource = {
    val cfg = new HikariConfig()
    cfg.setPoolName(name)
    cfg.setDriverClassName("org.sqlite.JDBC")
    cfg.setJdbcUrl(s"jdbc:sqlite:$file")
    cfg.setMaximumPoolSize(size)
    cfg.setMinimumIdle(size)
    // sqlite-jdbc applies these pragmas on every new connection.
    cfg.addDataSourceProperty("journal_mode", "WAL")
    cfg.addDataSourceProperty("busy_timeout", "5000")
    cfg.addDataSourceProperty("foreign_keys", "true")
    cfg.addDataSourceProperty("synchronous", "NORMAL")
    new HikariDataSource(cfg)
  }

  private def database(name: String, size: Int): Database = {
    val ds = dataSource(name, size)
    Database.forDataSource(ds, Some(size), AsyncExecutor(name, size, size, 10000, size))
  }

  val writer: Database = database("sqlite-writer", 1)
  val reader: Database = database("sqlite-reader", readerThreads)

  def write[R](a: DBIO[R]): Future[R] = writer.run(a.transactionally)
  def read[R](a: DBIO[R]): Future[R] = reader.run(a)

  /** Creates the schema if missing and logs the effective pragmas. */
  def init(): Unit = {
    val ddl = DBIO.seq(Schema.statements.map(s => sqlu"#$s")*)
    Await.result(writer.run(ddl.transactionally), 30.seconds)
    given ExecutionContext = ExecutionContext.parasitic
    val pragmas = for {
      mode <- sql"PRAGMA journal_mode".as[String].head
      timeout <- sql"PRAGMA busy_timeout".as[Int].head
    } yield (mode, timeout)
    val (wMode, wTimeout) = Await.result(writer.run(pragmas), 30.seconds)
    val (rMode, rTimeout) = Await.result(reader.run(pragmas), 30.seconds)
    log.info(
      s"SQLite ready at $file (writer: journal_mode=$wMode busy_timeout=$wTimeout; " +
        s"reader x$readerThreads: journal_mode=$rMode busy_timeout=$rTimeout)"
    )
  }

  def close(): Unit = {
    reader.close()
    writer.close()
  }
}
