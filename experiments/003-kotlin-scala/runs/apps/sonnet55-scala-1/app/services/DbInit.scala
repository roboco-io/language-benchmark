package services

import models.Tables.*
import play.api.db.slick.DatabaseConfigProvider
import play.api.inject.{SimpleModule, bind}
import slick.jdbc.JdbcProfile
import slick.jdbc.SQLiteProfile.api.*

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** Creates the schema (idempotently) when the application starts; runs eagerly. */
@Singleton
class DbInit @Inject() (dbConfigProvider: DatabaseConfigProvider) {
  private val db = dbConfigProvider.get[JdbcProfile].db

  private val setup = DBIO.seq(
    sqlu"""CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL UNIQUE, username TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL, bio TEXT, image TEXT)""",
    sqlu"""CREATE TABLE IF NOT EXISTS follows (
      follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      PRIMARY KEY (follower_id, followee_id))""",
    sqlu"""CREATE TABLE IF NOT EXISTS articles (
      id INTEGER PRIMARY KEY AUTOINCREMENT, slug TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
      description TEXT NOT NULL, body TEXT NOT NULL,
      author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""",
    sqlu"""CREATE TABLE IF NOT EXISTS article_tags (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE, name TEXT NOT NULL)""",
    sqlu"""CREATE TABLE IF NOT EXISTS favorites (
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      PRIMARY KEY (user_id, article_id))""",
    sqlu"""CREATE TABLE IF NOT EXISTS comments (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
      author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      body TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""",
    sqlu"CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id)",
    sqlu"CREATE INDEX IF NOT EXISTS idx_tags_article ON article_tags(article_id)",
    sqlu"CREATE INDEX IF NOT EXISTS idx_tags_name ON article_tags(name)",
    sqlu"CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id)"
  )

  Await.result(db.run(setup), Duration(30, TimeUnit.SECONDS))
}

class DbInitModule extends SimpleModule(bind[DbInit].toSelf.eagerly())
