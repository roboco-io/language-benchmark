package realworld.services

import realworld.db.Tables.*
import realworld.db.{ArticleRow, Db, UserRow}
import realworld.http.ApiError
import slick.jdbc.SQLiteProfile.api.*

import java.security.SecureRandom
import java.text.Normalizer
import scala.concurrent.{ExecutionContext, Future}

final case class ArticleFilter(tag: Option[String], author: Option[String], favorited: Option[String])

final case class ArticlePatch(
    title: Option[String],
    description: Option[String],
    body: Option[String],
    tagList: Option[Seq[String]]
)

final class ArticleService(db: Db)(using ec: ExecutionContext) {
  import ArticleService.*

  // ---- reading -------------------------------------------------------------------------------

  /** Loads authors, tags and favorite information for a page of articles in a fixed number of queries. */
  private[services] def enrich(rows: Seq[ArticleRow], viewer: Option[Long]): DBIO[Seq[ArticleView]] =
    if (rows.isEmpty) DBIO.successful(Seq.empty)
    else {
      val ids = rows.map(_.id).toSet
      val authorIds = rows.map(_.authorId).toSet
      for {
        authors <- users.filter(_.id inSet authorIds).result
        followed <- viewer.fold(DBIO.successful(Seq.empty[Long]))(v =>
          follows.filter(f => f.followerId === v && f.followeeId.inSet(authorIds)).map(_.followeeId).result
        )
        tagRows <- articleTags
          .filter(_.articleId inSet ids)
          .join(tags)
          .on(_.tagId === _.id)
          .sortBy { case (at, _) => (at.articleId, at.position) }
          .map { case (at, t) => (at.articleId, t.name) }
          .result
        counts <- favorites.filter(_.articleId inSet ids).groupBy(_.articleId).map((id, g) => (id, g.length)).result
        mine <- viewer.fold(DBIO.successful(Seq.empty[Long]))(v =>
          favorites.filter(f => f.userId === v && f.articleId.inSet(ids)).map(_.articleId).result
        )
      } yield {
        val authorMap = authors.map(u => u.id -> u).toMap
        val followedSet = followed.toSet
        val tagMap = tagRows.groupMap(_._1)(_._2)
        val countMap = counts.toMap
        val mineSet = mine.toSet
        rows.map { a =>
          val u = authorMap(a.authorId)
          ArticleView(
            slug = a.slug,
            title = a.title,
            description = a.description,
            body = a.body,
            tagList = tagMap.getOrElse(a.id, Seq.empty),
            createdAt = a.createdAt,
            updatedAt = a.updatedAt,
            favorited = mineSet(a.id),
            favoritesCount = countMap.getOrElse(a.id, 0),
            author = ProfileView(u.username, u.bio, u.image, followedSet(u.id))
          )
        }
      }
    }

  private def page(q: Query[Articles, ArticleRow, Seq], limit: Int, offset: Int, viewer: Option[Long]) =
    db.read(for {
      count <- q.length.result
      rows <- q.sortBy(a => (a.createdAt.desc, a.id.desc)).drop(offset).take(limit).result
      views <- enrich(rows, viewer)
    } yield (views, count))

  def list(filter: ArticleFilter, limit: Int, offset: Int, viewer: Option[Long]): Future[(Seq[ArticleView], Int)] = {
    var q: Query[Articles, ArticleRow, Seq] = articles
    filter.tag.foreach { name =>
      q = q.filter(a =>
        articleTags.join(tags).on(_.tagId === _.id).filter((at, t) => at.articleId === a.id && t.name === name).exists
      )
    }
    filter.author.foreach { name =>
      q = q.filter(a => users.filter(u => u.id === a.authorId && u.username === name).exists)
    }
    filter.favorited.foreach { name =>
      q = q.filter(a =>
        favorites.join(users).on(_.userId === _.id).filter((f, u) => f.articleId === a.id && u.username === name).exists
      )
    }
    page(q, limit, offset, viewer)
  }

  def feed(viewer: Long, limit: Int, offset: Int): Future[(Seq[ArticleView], Int)] = {
    val q = articles.filter(a => follows.filter(f => f.followerId === viewer && f.followeeId === a.authorId).exists)
    page(q, limit, offset, Some(viewer))
  }

  private def bySlug(slug: String): DBIO[ArticleRow] =
    articles.filter(_.slug === slug).result.headOption.flatMap {
      case Some(a) => DBIO.successful(a)
      case None    => DBIO.failed(ApiError.notFound("article"))
    }

  private def view(row: ArticleRow, viewer: Option[Long]): DBIO[ArticleView] = enrich(Seq(row), viewer).map(_.head)

  def get(slug: String, viewer: Option[Long]): Future[ArticleView] =
    db.read(bySlug(slug).flatMap(view(_, viewer)))

  def tagNames: Future[Seq[String]] =
    db.read(
      articleTags
        .join(tags)
        .on(_.tagId === _.id)
        .groupBy { case (_, t) => t.name }
        .map((name, g) => (name, g.length))
        .sortBy((name, n) => (n.desc, name))
        .map(_._1)
        .result
    )

  // ---- writing -------------------------------------------------------------------------------

  private def uniqueSlug(title: String, exceptId: Option[Long]): DBIO[String] = {
    val base = slugify(title)
    articles.filter(_.slug === base).map(_.id).result.headOption.map {
      case Some(id) if !exceptId.contains(id) => s"$base-${randomSuffix()}"
      case _                                  => base
    }
  }

  private def setTags(articleId: Long, names: Seq[String]): DBIO[Unit] =
    for {
      _ <- articleTags.filter(_.articleId === articleId).delete
      _ <- DBIO.sequence(names.map(n => sqlu"INSERT OR IGNORE INTO tags (name) VALUES ($n)"))
      rows <- if (names.isEmpty) DBIO.successful(Seq.empty) else tags.filter(_.name inSet names).result
      idByName = rows.map((id, name) => name -> id).toMap
      _ <- articleTags ++= names.zipWithIndex.map((n, i) => (articleId, idByName(n), i))
    } yield ()

  def create(author: UserRow, title: String, description: String, body: String, tagList: Seq[String]): Future[ArticleView] = {
    val now = System.currentTimeMillis()
    val names = normalizeTags(tagList)
    val action = for {
      slug <- uniqueSlug(title, None)
      _ <- articles.map(a => (a.slug, a.title, a.description, a.body, a.authorId, a.createdAt, a.updatedAt)) +=
        ((slug, title, description, body, author.id, now, now))
      id <- sql"SELECT last_insert_rowid()".as[Long].head
      _ <- setTags(id, names)
      row = ArticleRow(id, slug, title, description, body, author.id, now, now)
      v <- view(row, Some(author.id))
    } yield v
    db.write(action)
  }

  private def owned(slug: String, user: UserRow): DBIO[ArticleRow] =
    bySlug(slug).flatMap { a =>
      if (a.authorId == user.id) DBIO.successful(a) else DBIO.failed(ApiError.forbidden("article"))
    }

  def update(slug: String, user: UserRow, patch: ArticlePatch): Future[ArticleView] = {
    val action = for {
      a <- owned(slug, user)
      newTitle = patch.title.getOrElse(a.title)
      newSlug <- if (newTitle != a.title) uniqueSlug(newTitle, Some(a.id)) else DBIO.successful(a.slug)
      updated = a.copy(
        slug = newSlug,
        title = newTitle,
        description = patch.description.getOrElse(a.description),
        body = patch.body.getOrElse(a.body),
        updatedAt = math.max(System.currentTimeMillis(), a.updatedAt + 1)
      )
      _ <- articles.filter(_.id === a.id).update(updated)
      _ <- patch.tagList.fold(DBIO.successful(()))(t => setTags(a.id, normalizeTags(t)))
      v <- view(updated, Some(user.id))
    } yield v
    db.write(action)
  }

  def delete(slug: String, user: UserRow): Future[Unit] =
    db.write(owned(slug, user).flatMap(a => articles.filter(_.id === a.id).delete).map(_ => ()))

  def favorite(slug: String, user: UserRow): Future[ArticleView] =
    db.write(for {
      a <- bySlug(slug)
      _ <- sqlu"INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (${user.id}, ${a.id})"
      v <- view(a, Some(user.id))
    } yield v)

  def unfavorite(slug: String, user: UserRow): Future[ArticleView] =
    db.write(for {
      a <- bySlug(slug)
      _ <- favorites.filter(f => f.userId === user.id && f.articleId === a.id).delete
      v <- view(a, Some(user.id))
    } yield v)

  /** Resolves a slug to the article id, failing with 404 when it does not exist. */
  private[services] def idBySlug(slug: String): DBIO[Long] = bySlug(slug).map(_.id)
}

object ArticleService {
  private val random = new SecureRandom()
  private val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

  private def randomSuffix(): String = (1 to 6).map(_ => alphabet.charAt(random.nextInt(alphabet.length))).mkString

  def slugify(title: String): String = {
    val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
    val slug = ascii.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")
    if (slug.isEmpty) "article" else slug
  }

  def normalizeTags(tags: Seq[String]): Seq[String] = tags.map(_.trim).filter(_.nonEmpty).distinct
}
