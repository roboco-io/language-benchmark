package services

import java.nio.file.{Files, Paths}
import javax.inject.{Inject, Singleton}

import com.google.inject.AbstractModule
import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import slick.jdbc.SQLiteProfile.api.*
import slick.util.AsyncExecutor

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/** Owns the SQLite connection pool and the Slick database; sized to the number of CPU cores. */
@Singleton
class Db @Inject() (config: Configuration, lifecycle: ApplicationLifecycle):
  val cores: Int = Runtime.getRuntime.availableProcessors()

  private val path = config.get[String]("app.db.path")
  Option(Paths.get(path).toAbsolutePath.getParent).foreach(Files.createDirectories(_))

  private val dataSource: HikariDataSource =
    val hc = HikariConfig()
    // WAL journaling lets readers run beside a writer; busy_timeout makes writers wait up to
    // 5 s for the lock instead of failing immediately; IMMEDIATE transactions take the write
    // lock up front, which avoids lock-upgrade deadlocks between concurrent writers.
    hc.setJdbcUrl(s"jdbc:sqlite:$path?journal_mode=WAL&busy_timeout=5000&foreign_keys=true&synchronous=NORMAL&transaction_mode=IMMEDIATE")
    hc.setDriverClassName("org.sqlite.JDBC")
    hc.setMaximumPoolSize(cores)
    hc.setMinimumIdle(cores)
    hc.setPoolName("sqlite")
    HikariDataSource(hc)

  val database: Database =
    Database.forDataSource(dataSource, Some(cores), AsyncExecutor("slick", cores, cores, 10000, cores))

  Await.result(database.run(Schema.create), 30.seconds)

  lifecycle.addStopHook(() => Future.successful(database.close()))

class DbModule extends AbstractModule:
  override def configure(): Unit = bind(classOf[Db]).asEagerSingleton()
