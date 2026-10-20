package services

import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.{Inject, Singleton}

import slick.jdbc.SQLiteProfile.api.*
import common.{ApiException, Passwords}

import scala.concurrent.{ExecutionContext, Future}

/** Optional-field patch: absent keeps the stored value, `Some(None)` clears it. */
type Patch[A] = Option[Option[A]]

@Singleton
class Repo @Inject() (db: Db)(using ec: ExecutionContext):
  import Schema.*

  private def now(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString

  private def run[A](action: DBIO[A]): Future[A] =
    db.database.run(action).recoverWith {
      // A unique-constraint race that slipped past the explicit checks still maps to a 409.
      case e: SQLException if e.getMessage != null && e.getMessage.contains("UNIQUE constraint failed") =>
        val field = if e.getMessage.contains("users.email") then "email" else if e.getMessage.contains("users.username") then "username" else "request"
        Future.failed(ApiException(409, field, "has already been taken"))
    }

  private def tx[A](action: DBIO[A]): Future[A] = run(action.transactionally)

  // ---------------------------------------------------------------- users

  def userById(id: Long): Future[Option[UserRow]] = run(users.filter(_.id === id).result.headOption)

  def register(username: String, email: String, password: String): Future[UserRow] = tx {
    for
      byName <- users.filter(_.username === username).exists.result
      byMail <- users.filter(_.email === email).exists.result
      errors = Seq("username" -> byName, "email" -> byMail).collect { case (f, true) => f -> Seq("has already been taken") }.toMap
      _ <- if errors.nonEmpty then DBIO.failed(ApiException(409, errors)) else DBIO.successful(())
      id <- (users returning users.map(_.id)) += UserRow(0, username, email, Passwords.hash(password), None, None)
      user <- users.filter(_.id === id).result.head
    yield user
  }

  def authenticate(email: String, password: String): Future[UserRow] =
    run(users.filter(_.email === email).result.headOption).map {
      case Some(u) if Passwords.check(password, u.passwordHash) => u
      case _ => throw ApiException(401, "credentials", "invalid")
    }

  def updateUser(id: Long, username: Option[String], email: Option[String], password: Option[String],
                 bio: Patch[String], image: Patch[String]): Future[UserRow] = tx {
    for
      current <- users.filter(_.id === id).result.head
      nameTaken <- username.filter(_ != current.username).fold(DBIO.successful(false))(n => users.filter(_.username === n).exists.result)
      mailTaken <- email.filter(_ != current.email).fold(DBIO.successful(false))(m => users.filter(_.email === m).exists.result)
      errors = Seq("username" -> nameTaken, "email" -> mailTaken).collect { case (f, true) => f -> Seq("has already been taken") }.toMap
      _ <- if errors.nonEmpty then DBIO.failed(ApiException(409, errors)) else DBIO.successful(())
      updated = current.copy(
        username = username.getOrElse(current.username),
        email = email.getOrElse(current.email),
        passwordHash = password.fold(current.passwordHash)(Passwords.hash),
        bio = bio.getOrElse(current.bio),
        image = image.getOrElse(current.image))
      _ <- users.filter(_.id === id).update(updated)
    yield updated
  }

  // ------------------------------------------------------------- profiles

  private def profileOf(u: UserRow, following: Boolean) = ProfileView(u.username, u.bio, u.image, following)

  private def isFollowing(viewer: Option[Long], authorId: Long): DBIO[Boolean] =
    viewer.fold(DBIO.successful(false))(v => follows.filter(f => f.followerId === v && f.followedId === authorId).exists.result)

  private def userByName(username: String): DBIO[UserRow] =
    users.filter(_.username === username).result.headOption.map(_.getOrElse(throw ApiException.notFound("profile")))

  def profile(username: String, viewer: Option[Long]): Future[ProfileView] = run {
    for
      u <- userByName(username)
      f <- isFollowing(viewer, u.id)
    yield profileOf(u, f)
  }

  def setFollow(username: String, viewer: Long, follow: Boolean): Future[ProfileView] = tx {
    for
      u <- userByName(username)
      q = follows.filter(f => f.followerId === viewer && f.followedId === u.id)
      exists <- q.exists.result
      _ <- if follow && !exists then follows += ((viewer, u.id))
           else if !follow && exists then q.delete
           else DBIO.successful(0)
    yield profileOf(u, follow)
  }

  // ------------------------------------------------------------- articles

  private def slugify(title: String): String =
    val base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")
    val prefix = if base.isEmpty then "article" else base.take(80)
    s"$prefix-${java.lang.Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 30, 1L << 40), 36)}"

  /** Attaches tags, favorite state and author profiles to a page of article rows with batched queries. */
  private def toViews(rows: Seq[(ArticleRow, UserRow)], viewer: Option[Long]): DBIO[Seq[ArticleView]] =
    if rows.isEmpty then DBIO.successful(Seq.empty)
    else
      val ids = rows.map(_._1.id)
      val authorIds = rows.map(_._2.id).distinct
      for
        tagRows <- articleTags.filter(_.articleId inSet ids).result
        counts <- favorites.filter(_.articleId inSet ids).groupBy(_.articleId).map((a, g) => (a, g.length)).result
        favd <- viewer.fold(DBIO.successful(Seq.empty[Long]))(v => favorites.filter(f => f.userId === v && (f.articleId inSet ids)).map(_.articleId).result)
        fols <- viewer.fold(DBIO.successful(Seq.empty[Long]))(v => follows.filter(f => f.followerId === v && (f.followedId inSet authorIds)).map(_.followedId).result)
      yield
        val tagMap = tagRows.groupBy(_._1).view.mapValues(_.map(_._2).sorted).toMap
        val countMap = counts.toMap
        val favSet = favd.toSet
        val folSet = fols.toSet
        rows.map { (a, u) =>
          ArticleView(a, tagMap.getOrElse(a.id, Seq.empty), favSet(a.id), countMap.getOrElse(a.id, 0), profileOf(u, folSet(u.id)))
        }

  private def articleBySlug(slug: String): DBIO[ArticleRow] =
    articles.filter(_.slug === slug).result.headOption.map(_.getOrElse(throw ApiException.notFound("article")))

  private def viewOne(a: ArticleRow, viewer: Option[Long]): DBIO[ArticleView] =
    for
      author <- users.filter(_.id === a.authorId).result.head
      views <- toViews(Seq((a, author)), viewer)
    yield views.head

  def article(slug: String, viewer: Option[Long]): Future[ArticleView] = run(articleBySlug(slug).flatMap(viewOne(_, viewer)))

  def listArticles(tag: Option[String], author: Option[String], favoritedBy: Option[String],
                   limit: Int, offset: Int, viewer: Option[Long]): Future[(Seq[ArticleView], Int)] = run {
    val query = articles.join(users).on(_.authorId === _.id)
      .filterOpt(tag)((q, t) => q._1.id in articleTags.filter(_.name === t).map(_.articleId))
      .filterOpt(author)((q, a) => q._2.username === a)
      .filterOpt(favoritedBy)((q, f) => q._1.id in favorites.join(users).on(_.userId === _.id).filter(_._2.username === f).map(_._1.articleId))
    page(query, limit, offset, viewer)
  }

  def feed(viewer: Long, limit: Int, offset: Int): Future[(Seq[ArticleView], Int)] = run {
    val query = articles.join(users).on(_.authorId === _.id)
      .filter(q => q._1.authorId in follows.filter(_.followerId === viewer).map(_.followedId))
    page(query, limit, offset, Some(viewer))
  }

  private def page(query: Query[(Articles, Users), (ArticleRow, UserRow), Seq], limit: Int, offset: Int,
                   viewer: Option[Long]): DBIO[(Seq[ArticleView], Int)] =
    for
      total <- query.length.result
      rows <- query.sortBy(_._1.id.desc).drop(offset).take(limit).result
      views <- toViews(rows, viewer)
    yield (views, total)

  def createArticle(authorId: Long, title: String, description: String, body: String, tagList: Seq[String]): Future[ArticleView] = tx {
    val ts = now()
    for
      id <- (articles returning articles.map(_.id)) += ArticleRow(0, slugify(title), title, description, body, authorId, ts, ts)
      _ <- articleTags ++= tagList.distinct.map(id -> _)
      row <- articles.filter(_.id === id).result.head
      view <- viewOne(row, Some(authorId))
    yield view
  }

  def updateArticle(slug: String, userId: Long, title: Option[String], description: Option[String], body: Option[String],
                    tagList: Option[Seq[String]]): Future[ArticleView] = tx {
    for
      current <- articleBySlug(slug)
      _ = if current.authorId != userId then throw ApiException.forbidden("article")
      updated = current.copy(
        title = title.getOrElse(current.title),
        description = description.getOrElse(current.description),
        body = body.getOrElse(current.body),
        updatedAt = now())
      _ <- articles.filter(_.id === current.id).update(updated)
      _ <- tagList.fold(DBIO.successful(()): DBIO[Unit]) { tags =>
        articleTags.filter(_.articleId === current.id).delete.andThen(articleTags ++= tags.distinct.map(current.id -> _)).map(_ => ())
      }
      view <- viewOne(updated, Some(userId))
    yield view
  }

  def deleteArticle(slug: String, userId: Long): Future[Unit] = tx {
    for
      current <- articleBySlug(slug)
      _ = if current.authorId != userId then throw ApiException.forbidden("article")
      _ <- articles.filter(_.id === current.id).delete
    yield ()
  }

  def setFavorite(slug: String, userId: Long, favorite: Boolean): Future[ArticleView] = tx {
    for
      current <- articleBySlug(slug)
      q = favorites.filter(f => f.userId === userId && f.articleId === current.id)
      exists <- q.exists.result
      _ <- if favorite && !exists then favorites += ((userId, current.id))
           else if !favorite && exists then q.delete
           else DBIO.successful(0)
      view <- viewOne(current, Some(userId))
    yield view
  }

  def tags: Future[Seq[String]] = run(articleTags.map(_.name).distinct.sorted.result)

  // ------------------------------------------------------------- comments

  private def commentViews(rows: Seq[(CommentRow, UserRow)], viewer: Option[Long]): DBIO[Seq[CommentView]] =
    val authorIds = rows.map(_._2.id).distinct
    for
      fols <- viewer.filter(_ => authorIds.nonEmpty).fold(DBIO.successful(Seq.empty[Long]))(v =>
        follows.filter(f => f.followerId === v && (f.followedId inSet authorIds)).map(_.followedId).result)
    yield rows.map((c, u) => CommentView(c, profileOf(u, fols.contains(u.id))))

  def commentsOf(slug: String, viewer: Option[Long]): Future[Seq[CommentView]] = run {
    for
      a <- articleBySlug(slug)
      rows <- comments.filter(_.articleId === a.id).join(users).on(_.authorId === _.id).sortBy(_._1.id.desc).result
      views <- commentViews(rows, viewer)
    yield views
  }

  def addComment(slug: String, userId: Long, body: String): Future[CommentView] = tx {
    val ts = now()
    for
      a <- articleBySlug(slug)
      id <- (comments returning comments.map(_.id)) += CommentRow(0, a.id, userId, body, ts, ts)
      rows <- comments.filter(_.id === id).join(users).on(_.authorId === _.id).result
      views <- commentViews(rows, Some(userId))
    yield views.head
  }

  def deleteComment(slug: String, commentId: Long, userId: Long): Future[Unit] = tx {
    for
      a <- articleBySlug(slug)
      c <- comments.filter(c => c.id === commentId && c.articleId === a.id).result.headOption
        .map(_.getOrElse(throw ApiException.notFound("comment")))
      _ = if c.authorId != userId then throw ApiException.forbidden("comment")
      _ <- comments.filter(_.id === commentId).delete
    yield ()
  }
