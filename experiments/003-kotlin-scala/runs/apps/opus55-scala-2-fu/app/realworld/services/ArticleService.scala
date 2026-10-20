package realworld.services

import realworld.ApiError
import realworld.db.{ArticleRow, CommentRow, Tables}
import realworld.db.Tables.*
import slick.jdbc.SQLiteProfile.api.*

import java.text.Normalizer
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.{ExecutionContext, Future}

/** An article with everything needed to render it for a given viewer. */
final case class ArticleView(
    article: ArticleRow,
    tags: Seq[String],
    author: Profile,
    favorited: Boolean,
    favoritesCount: Int
)

final case class CommentView(comment: CommentRow, author: Profile)

final case class ArticleFilter(
    tag: Option[String] = None,
    author: Option[String] = None,
    favorited: Option[String] = None,
    feedOf: Option[Long] = None
)

final case class ArticleUpdate(
    title: Option[String],
    description: Option[String],
    body: Option[String],
    tagList: Option[Seq[String]]
)

class ArticleService(db: Database)(using ec: ExecutionContext):

  // ---- Queries -------------------------------------------------------------

  def list(filter: ArticleFilter, limit: Int, offset: Int, viewer: Option[Long]): Future[(Seq[ArticleView], Int)] =
    var q = articles.to[Seq]
    filter.tag.foreach { t =>
      q = q.filter(a =>
        articleTags.join(tags).on(_.tagId === _.id).filter((at, tg) => at.articleId === a.id && tg.name === t).exists
      )
    }
    filter.author.foreach { n =>
      q = q.filter(a => users.filter(u => u.id === a.authorId && u.username === n).exists)
    }
    filter.favorited.foreach { n =>
      q = q.filter(a =>
        favorites.join(users).on(_.userId === _.id).filter((f, u) => f.articleId === a.id && u.username === n).exists
      )
    }
    filter.feedOf.foreach { me =>
      q = q.filter(a => follows.filter(f => f.followerId === me && f.followeeId === a.authorId).exists)
    }
    val action = for
      count <- q.length.result
      rows  <- q.sortBy(a => (a.createdAt.desc, a.id.desc)).drop(offset).take(limit).result
      views <- enrich(rows, viewer)
    yield (views, count)
    db.run(action)

  def get(slug: String, viewer: Option[Long]): Future[ArticleView] =
    db.run(bySlug(slug).flatMap(a => enrichOne(a, viewer)))

  def tagNames(): Future[Seq[String]] =
    val used = tags.filter(t => articleTags.filter(_.tagId === t.id).exists)
    db.run(used.sortBy(_.name).map(_.name).result)

  // ---- Article writes ------------------------------------------------------

  def create(
      authorId: Long,
      title: String,
      description: String,
      body: String,
      tagList: Seq[String]
  ): Future[ArticleView] =
    val now = System.currentTimeMillis()
    val action = for
      slug <- uniqueSlug(title, None)
      id <- (articles returning articles.map(_.id)) +=
        ArticleRow(0, slug, title, description, body, authorId, now, now)
      _       <- replaceTags(id, tagList)
      article <- articles.filter(_.id === id).result.head
      view    <- enrichOne(article, Some(authorId))
    yield view
    db.run(action.transactionally)

  def update(slug: String, userId: Long, change: ArticleUpdate): Future[ArticleView] =
    val action = for
      current <- ownedArticle(slug, userId)
      newSlug <- change.title.filter(_ != current.title) match
        case Some(t) => uniqueSlug(t, Some(current.id))
        case None    => DBIO.successful(current.slug)
      // Keep updatedAt strictly increasing even for updates within the same millisecond.
      now = math.max(System.currentTimeMillis(), current.updatedAt + 1)
      updated = current.copy(
        slug = newSlug,
        title = change.title.getOrElse(current.title),
        description = change.description.getOrElse(current.description),
        body = change.body.getOrElse(current.body),
        updatedAt = now
      )
      _    <- articles.filter(_.id === current.id).update(updated)
      _    <- change.tagList.fold(DBIO.successful(()))(t => replaceTags(current.id, t))
      view <- enrichOne(updated, Some(userId))
    yield view
    db.run(action.transactionally)

  def delete(slug: String, userId: Long): Future[Unit] =
    val action = for
      article <- ownedArticle(slug, userId)
      _       <- Tables.comments.filter(_.articleId === article.id).delete
      _       <- favorites.filter(_.articleId === article.id).delete
      _       <- articleTags.filter(_.articleId === article.id).delete
      _       <- articles.filter(_.id === article.id).delete
    yield ()
    db.run(action.transactionally)

  def favorite(slug: String, userId: Long): Future[ArticleView] =
    val action = for
      article <- bySlug(slug)
      _       <- sqlu"INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES ($userId, ${article.id})"
      view    <- enrichOne(article, Some(userId))
    yield view
    db.run(action.transactionally)

  def unfavorite(slug: String, userId: Long): Future[ArticleView] =
    val action = for
      article <- bySlug(slug)
      _       <- favorites.filter(f => f.userId === userId && f.articleId === article.id).delete
      view    <- enrichOne(article, Some(userId))
    yield view
    db.run(action.transactionally)

  // ---- Comments ------------------------------------------------------------

  def comments(slug: String, viewer: Option[Long]): Future[Seq[CommentView]] =
    val action = for
      article <- bySlug(slug)
      rows    <- Tables.comments.filter(_.articleId === article.id).sortBy(_.id.asc).result
      authors <- profiles(rows.map(_.authorId).toSet, viewer)
    yield rows.map(c => CommentView(c, authors(c.authorId)))
    db.run(action)

  def addComment(slug: String, userId: Long, body: String): Future[CommentView] =
    val now = System.currentTimeMillis()
    val action = for
      article <- bySlug(slug)
      id <- (Tables.comments returning Tables.comments.map(_.id)) +=
        CommentRow(0, body, article.id, userId, now, now)
      authors <- profiles(Set(userId), Some(userId))
    yield CommentView(CommentRow(id, body, article.id, userId, now, now), authors(userId))
    db.run(action.transactionally)

  def updateComment(slug: String, commentId: Long, userId: Long, body: String): Future[CommentView] =
    val now = System.currentTimeMillis()
    val action = for
      article <- bySlug(slug)
      comment <- Tables.comments.filter(c => c.id === commentId && c.articleId === article.id).result.headOption
      updated <- comment match
        case None                             => DBIO.failed(ApiError.notFound("comment"))
        case Some(c) if c.authorId != userId => DBIO.failed(ApiError.forbidden("comment"))
        case Some(c) =>
          // Keep updatedAt strictly after the previous value even within the same millisecond.
          val stamp = math.max(now, c.updatedAt + 1)
          Tables.comments.filter(_.id === c.id).map(r => (r.body, r.updatedAt)).update((body, stamp))
            .map(_ => c.copy(body = body, updatedAt = stamp))
      authors <- profiles(Set(userId), Some(userId))
    yield CommentView(updated, authors(userId))
    db.run(action.transactionally)

  def deleteComment(slug: String, commentId: Long, userId: Long): Future[Unit] =
    val action = for
      article <- bySlug(slug)
      comment <- Tables.comments.filter(c => c.id === commentId && c.articleId === article.id).result.headOption
      _ <- comment match
        case None                             => DBIO.failed(ApiError.notFound("comment"))
        case Some(c) if c.authorId != userId => DBIO.failed(ApiError.forbidden("comment"))
        case Some(c)                          => Tables.comments.filter(_.id === c.id).delete
    yield ()
    db.run(action.transactionally)

  // ---- Helpers -------------------------------------------------------------

  private def bySlug(slug: String): DBIO[ArticleRow] =
    articles.filter(_.slug === slug).result.headOption.flatMap {
      case Some(a) => DBIO.successful(a)
      case None    => DBIO.failed(ApiError.notFound("article"))
    }

  private def ownedArticle(slug: String, userId: Long): DBIO[ArticleRow] =
    bySlug(slug).flatMap { a =>
      if a.authorId == userId then DBIO.successful(a) else DBIO.failed(ApiError.forbidden("article"))
    }

  private def replaceTags(articleId: Long, tagList: Seq[String]): DBIO[Unit] =
    val names = tagList.map(_.trim).filter(_.nonEmpty).distinct
    for
      _ <- articleTags.filter(_.articleId === articleId).delete
      _ <- DBIO.sequence(names.map(n => sqlu"INSERT OR IGNORE INTO tags (name) VALUES ($n)"))
      ids <- tags.filter(_.name.inSet(names)).result
      byName = ids.map((id, name) => name -> id).toMap
      _ <- articleTags ++= names.zipWithIndex.map((n, i) => (articleId, byName(n), i))
    yield ()

  private def uniqueSlug(title: String, selfId: Option[Long]): DBIO[String] =
    val base = Slug(title)
    def taken(s: String) = articles.filter(a => a.slug === s && selfId.fold(true: Rep[Boolean])(a.id =!= _)).exists.result
    taken(base).flatMap {
      case false => DBIO.successful(base)
      case true  =>
        val candidate = s"$base-${Slug.suffix()}"
        taken(candidate).map(t => if t then s"$candidate-${Slug.suffix()}" else candidate)
    }

  private def enrichOne(article: ArticleRow, viewer: Option[Long]): DBIO[ArticleView] =
    enrich(Seq(article), viewer).map(_.head)

  /** Loads tags, authors, favorite counts and viewer flags for a page of articles in a fixed number of queries. */
  private def enrich(rows: Seq[ArticleRow], viewer: Option[Long]): DBIO[Seq[ArticleView]] =
    if rows.isEmpty then DBIO.successful(Seq.empty)
    else
      val ids = rows.map(_.id).toSet
      for
        tagRows <- articleTags
          .join(tags)
          .on(_.tagId === _.id)
          .filter(_._1.articleId.inSet(ids))
          .sortBy((at, _) => (at.articleId, at.position))
          .map((at, t) => (at.articleId, t.name))
          .result
        counts <- favorites
          .filter(_.articleId.inSet(ids))
          .groupBy(_.articleId)
          .map((id, g) => (id, g.length))
          .result
        mine <- viewer match
          case Some(v) => favorites.filter(f => f.userId === v && f.articleId.inSet(ids)).map(_.articleId).result
          case None    => DBIO.successful(Seq.empty[Long])
        authors <- profiles(rows.map(_.authorId).toSet, viewer)
      yield
        val tagMap   = tagRows.groupMap(_._1)(_._2)
        val countMap = counts.toMap
        val mineSet  = mine.toSet
        rows.map { a =>
          ArticleView(
            a,
            tagMap.getOrElse(a.id, Seq.empty),
            authors(a.authorId),
            mineSet.contains(a.id),
            countMap.getOrElse(a.id, 0)
          )
        }

  private def profiles(userIds: Set[Long], viewer: Option[Long]): DBIO[Map[Long, Profile]] =
    for
      rows <- users.filter(_.id.inSet(userIds)).result
      followed <- viewer match
        case Some(v) => follows.filter(f => f.followerId === v && f.followeeId.inSet(userIds)).map(_.followeeId).result
        case None    => DBIO.successful(Seq.empty[Long])
    yield
      val f = followed.toSet
      rows.map(u => u.id -> Profile(u, f.contains(u.id))).toMap

object Slug:
  def apply(title: String): String =
    val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
    val slug  = ascii.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")
    if slug.isEmpty then "article" else slug

  def suffix(): String =
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val r     = ThreadLocalRandom.current()
    (1 to 8).map(_ => chars.charAt(r.nextInt(chars.length))).mkString
