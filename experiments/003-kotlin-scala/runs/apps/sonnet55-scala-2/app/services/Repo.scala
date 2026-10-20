package services

import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}

import slick.jdbc.{GetResult, SQLActionBuilder}
import slick.jdbc.SQLiteProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

case class ApiException(status: Int, errors: Map[String, List[String]]) extends RuntimeException(errors.toString)

object ApiException:
  def apply(status: Int, field: String, msg: String): ApiException = ApiException(status, Map(field -> List(msg)))

case class UserRow(id: Long, email: String, username: String, passwordHash: String, bio: Option[String], image: Option[String])
case class Profile(username: String, bio: Option[String], image: Option[String], following: Boolean)
case class ArticleView(
    id: Long, slug: String, title: String, description: String, body: String,
    createdAt: String, updatedAt: String, favoritesCount: Int, favorited: Boolean,
    author: Profile, tagList: List[String])
case class CommentView(id: Long, body: String, createdAt: String, updatedAt: String, author: Profile)

/** All SQL lives here, written with Slick's plain-SQL interpolation. */
@Singleton
class Repo @Inject() (database: Db)(using ec: ExecutionContext):
  private val db = database.db

  private given GetResult[UserRow] = GetResult(r => UserRow(r.<<, r.<<, r.<<, r.<<, r.<<, r.<<))

  private def now(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString

  private def isUnique(e: Throwable): Boolean = e match
    case s: SQLException => s.getMessage != null && s.getMessage.contains("UNIQUE")
    case _ => false

  // ---------------------------------------------------------------- users

  def findUserById(id: Long): Future[Option[UserRow]] =
    db.run(sql"SELECT id, email, username, password_hash, bio, image FROM users WHERE id = $id".as[UserRow].headOption)

  def findUserByEmail(email: String): Future[Option[UserRow]] =
    db.run(sql"SELECT id, email, username, password_hash, bio, image FROM users WHERE email = $email".as[UserRow].headOption)

  def findUserByUsername(username: String): Future[Option[UserRow]] =
    db.run(sql"SELECT id, email, username, password_hash, bio, image FROM users WHERE username = $username".as[UserRow].headOption)

  private def conflictFor(email: String, username: String, excludeId: Long): DBIO[Unit] =
    for
      e <- sql"SELECT COUNT(*) FROM users WHERE email = $email AND id <> $excludeId".as[Int].head
      u <- sql"SELECT COUNT(*) FROM users WHERE username = $username AND id <> $excludeId".as[Int].head
      errs = (if e > 0 then Map("email" -> List("has already been taken")) else Map.empty[String, List[String]]) ++
        (if u > 0 then Map("username" -> List("has already been taken")) else Map.empty)
      _ <- if errs.nonEmpty then DBIO.failed(ApiException(409, errs)) else DBIO.successful(())
    yield ()

  def createUser(email: String, username: String, passwordHash: String): Future[UserRow] =
    val action = (for
      _ <- conflictFor(email, username, 0L)
      _ <- sqlu"INSERT INTO users (email, username, password_hash) VALUES ($email, $username, $passwordHash)"
      id <- sql"SELECT last_insert_rowid()".as[Long].head
    yield UserRow(id, email, username, passwordHash, None, None)).transactionally
    db.run(action).recoverWith { case e if isUnique(e) =>
      Future.failed(ApiException(409, Map("username" -> List("has already been taken"))))
    }

  def updateUser(u: UserRow): Future[UserRow] =
    val action = (for
      _ <- conflictFor(u.email, u.username, u.id)
      _ <- sqlu"""UPDATE users SET email = ${u.email}, username = ${u.username},
                  password_hash = ${u.passwordHash}, bio = ${u.bio}, image = ${u.image} WHERE id = ${u.id}"""
    yield u).transactionally
    db.run(action)

  // ------------------------------------------------------------- profiles

  def profileOf(username: String, viewer: Option[Long]): Future[Option[Profile]] =
    val v = viewer.getOrElse(0L)
    db.run(
      sql"""SELECT u.username, u.bio, u.image,
                   EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = $v AND f.followee_id = u.id)
            FROM users u WHERE u.username = $username"""
        .as[(String, Option[String], Option[String], Boolean)].headOption
    ).map(_.map((n, b, i, f) => Profile(n, b, i, f)))

  def setFollow(follower: Long, followee: Long, on: Boolean): Future[Unit] =
    val action =
      if on then sqlu"INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES ($follower, $followee)"
      else sqlu"DELETE FROM follows WHERE follower_id = $follower AND followee_id = $followee"
    db.run(action.transactionally).map(_ => ())

  // ------------------------------------------------------------- articles

  private given GetResult[(Long, String, String, String, String, String, String, Int, Boolean, String, Option[String], Option[String], Boolean)] =
    GetResult(r => (r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<))

  private def articleSelect(viewer: Long) =
    sql"""SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at,
                 (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id),
                 EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = $viewer),
                 u.username, u.bio, u.image,
                 EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = $viewer AND w.followee_id = u.id)
          FROM articles a JOIN users u ON u.id = a.author_id """

  private def withTags(rows: Vector[(Long, String, String, String, String, String, String, Int, Boolean, String, Option[String], Option[String], Boolean)]): DBIO[Vector[ArticleView]] =
    if rows.isEmpty then DBIO.successful(Vector.empty)
    else
      val ids = rows.map(_._1).mkString(",")
      sql"SELECT article_id, tag FROM article_tags WHERE article_id IN (#$ids) ORDER BY rowid".as[(Long, String)].map { tags =>
        val byArticle = tags.groupMap(_._1)(_._2)
        rows.map { (id, slug, title, desc, body, c, u, fc, fav, un, bio, img, fol) =>
          ArticleView(id, slug, title, desc, body, c, u, fc, fav, Profile(un, bio, img, fol),
            byArticle.getOrElse(id, Vector.empty).toList)
        }
      }

  def findArticle(slug: String, viewer: Option[Long]): Future[Option[ArticleView]] =
    val v = viewer.getOrElse(0L)
    val q = articleSelect(v).concat(sql"WHERE a.slug = $slug")
    db.run(q.as[(Long, String, String, String, String, String, String, Int, Boolean, String, Option[String], Option[String], Boolean)]
      .flatMap(withTags).map(_.headOption))

  /** Returns (page, total). When `feedOf` is set, only articles by followed authors are returned. */
  def listArticles(
      tag: Option[String], author: Option[String], favoritedBy: Option[String], feedOf: Option[Long],
      viewer: Option[Long], limit: Int, offset: Int): Future[(Vector[ArticleView], Int)] =
    val v = viewer.getOrElse(0L)
    val feed = feedOf.getOrElse(0L)
    val isFeed = if feedOf.isDefined then 1 else 0
    def where =
      sql"""WHERE (${tag} IS NULL OR EXISTS (SELECT 1 FROM article_tags t WHERE t.article_id = a.id AND t.tag = ${tag}))
              AND (${author} IS NULL OR u.username = ${author})
              AND (${favoritedBy} IS NULL OR EXISTS (SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id
                                                     WHERE fv.article_id = a.id AND fu.username = ${favoritedBy}))
              AND ($isFeed = 0 OR a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = $feed)) """
    val page = articleSelect(v).concat(where).concat(sql"ORDER BY a.created_at DESC, a.id DESC LIMIT $limit OFFSET $offset")
      .as[(Long, String, String, String, String, String, String, Int, Boolean, String, Option[String], Option[String], Boolean)]
      .flatMap(withTags)
    val count = sql"SELECT COUNT(*) FROM articles a JOIN users u ON u.id = a.author_id ".concat(where).as[Int].head
    db.run(page.zip(count))

  private def slugify(title: String): String =
    val s = title.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")
    if s.isEmpty then "article" else s

  private def uniqueSlug(title: String, selfId: Long): DBIO[String] =
    val base = slugify(title)
    def attempt(candidate: String): DBIO[String] =
      sql"SELECT COUNT(*) FROM articles WHERE slug = $candidate AND id <> $selfId".as[Int].head.flatMap { n =>
        if n == 0 then DBIO.successful(candidate)
        else attempt(base + "-" + java.lang.Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 30, 1L << 31), 36))
      }
    attempt(base)

  private def insertTags(articleId: Long, tags: Seq[String]): DBIO[Unit] =
    DBIO.seq(tags.distinct.map(t => sqlu"INSERT OR IGNORE INTO article_tags (article_id, tag) VALUES ($articleId, $t)")*)

  def createArticle(authorId: Long, title: String, description: String, body: String, tags: Seq[String]): Future[String] =
    val ts = now()
    val action = (for
      slug <- uniqueSlug(title, 0L)
      _ <- sqlu"""INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at)
                  VALUES ($slug, $title, $description, $body, $authorId, $ts, $ts)"""
      id <- sql"SELECT last_insert_rowid()".as[Long].head
      _ <- insertTags(id, tags)
    yield slug).transactionally
    db.run(action)

  def updateArticle(a: ArticleView, title: String, description: String, body: String, tags: Option[Seq[String]]): Future[String] =
    val action = (for
      current <- sql"SELECT updated_at FROM articles WHERE id = ${a.id}".as[String].head
      // Guarantee a strictly newer timestamp even when two updates land in the same millisecond.
      ts = { val n = now(); if n > current then n else Instant.parse(current).plusMillis(1).toString }
      slug <- if title != a.title then uniqueSlug(title, a.id) else DBIO.successful(a.slug)
      _ <- sqlu"""UPDATE articles SET slug = $slug, title = $title, description = $description, body = $body,
                  updated_at = $ts WHERE id = ${a.id}"""
      _ <- tags match
        case Some(ts) => sqlu"DELETE FROM article_tags WHERE article_id = ${a.id}".andThen(insertTags(a.id, ts))
        case None => DBIO.successful(())
    yield slug).transactionally
    db.run(action)

  def deleteArticle(id: Long): Future[Unit] =
    db.run(sqlu"DELETE FROM articles WHERE id = $id".transactionally).map(_ => ())

  def setFavorite(userId: Long, articleId: Long, on: Boolean): Future[Unit] =
    val action =
      if on then sqlu"INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES ($userId, $articleId)"
      else sqlu"DELETE FROM favorites WHERE user_id = $userId AND article_id = $articleId"
    db.run(action.transactionally).map(_ => ())

  def allTags(): Future[Vector[String]] =
    db.run(sql"SELECT tag FROM article_tags GROUP BY tag ORDER BY MIN(rowid)".as[String])

  def articleAuthor(slug: String): Future[Option[(Long, Long)]] =
    db.run(sql"SELECT id, author_id FROM articles WHERE slug = $slug".as[(Long, Long)].headOption)

  // ------------------------------------------------------------- comments

  private def commentRows(where: SQLActionBuilder, viewer: Long) =
    sql"""SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,
                 EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = $viewer AND w.followee_id = u.id)
          FROM comments c JOIN users u ON u.id = c.author_id """.concat(where)
      .as[(Long, String, String, String, String, Option[String], Option[String], Boolean)]
      .map(_.map((id, b, c, u, n, bio, img, f) => CommentView(id, b, c, u, Profile(n, bio, img, f))))

  def listComments(articleId: Long, viewer: Option[Long]): Future[Vector[CommentView]] =
    db.run(commentRows(sql"WHERE c.article_id = $articleId ORDER BY c.created_at DESC, c.id DESC", viewer.getOrElse(0L)))

  def addComment(articleId: Long, authorId: Long, body: String): Future[CommentView] =
    val ts = now()
    val action = (for
      _ <- sqlu"INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES ($articleId, $authorId, $body, $ts, $ts)"
      id <- sql"SELECT last_insert_rowid()".as[Long].head
      c <- commentRows(sql"WHERE c.id = $id", authorId).map(_.head)
    yield c).transactionally
    db.run(action)

  def findComment(id: Long, articleId: Long): Future[Option[Long]] =
    db.run(sql"SELECT author_id FROM comments WHERE id = $id AND article_id = $articleId".as[Long].headOption)

  def deleteComment(id: Long): Future[Unit] =
    db.run(sqlu"DELETE FROM comments WHERE id = $id".transactionally).map(_ => ())
