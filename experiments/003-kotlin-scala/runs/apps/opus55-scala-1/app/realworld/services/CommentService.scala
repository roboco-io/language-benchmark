package realworld.services

import realworld.db.Tables.*
import realworld.db.{CommentRow, Db, UserRow}
import realworld.http.ApiError
import slick.jdbc.SQLiteProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

final class CommentService(db: Db, articlesService: ArticleService)(using ec: ExecutionContext) {

  private def views(rows: Seq[CommentRow], viewer: Option[Long]): DBIO[Seq[CommentView]] =
    if (rows.isEmpty) DBIO.successful(Seq.empty)
    else {
      val authorIds = rows.map(_.authorId).toSet
      for {
        authors <- users.filter(_.id inSet authorIds).result
        followed <- viewer.fold(DBIO.successful(Seq.empty[Long]))(v =>
          follows.filter(f => f.followerId === v && f.followeeId.inSet(authorIds)).map(_.followeeId).result
        )
      } yield {
        val authorMap = authors.map(u => u.id -> u).toMap
        val followedSet = followed.toSet
        rows.map { c =>
          val u = authorMap(c.authorId)
          CommentView(c.id, c.body, c.createdAt, c.updatedAt, ProfileView(u.username, u.bio, u.image, followedSet(u.id)))
        }
      }
    }

  def list(slug: String, viewer: Option[Long]): Future[Seq[CommentView]] =
    db.read(for {
      articleId <- articlesService.idBySlug(slug)
      rows <- comments.filter(_.articleId === articleId).sortBy(_.id.asc).result
      vs <- views(rows, viewer)
    } yield vs)

  def create(slug: String, author: UserRow, body: String): Future[CommentView] = {
    val now = System.currentTimeMillis()
    db.write(for {
      articleId <- articlesService.idBySlug(slug)
      _ <- comments.map(c => (c.articleId, c.authorId, c.body, c.createdAt, c.updatedAt)) +=
        ((articleId, author.id, body, now, now))
      id <- sql"SELECT last_insert_rowid()".as[Long].head
    } yield CommentView(id, body, now, now, ProfileView(author.username, author.bio, author.image, following = false)))
  }

  def delete(slug: String, id: Long, user: UserRow): Future[Unit] =
    db.write(for {
      articleId <- articlesService.idBySlug(slug)
      row <- comments.filter(c => c.id === id && c.articleId === articleId).result.headOption
      _ <- row match {
        case None                              => DBIO.failed(ApiError.notFound("comment"))
        case Some(c) if c.authorId != user.id => DBIO.failed(ApiError.forbidden("comment"))
        case Some(c)                           => comments.filter(_.id === c.id).delete
      }
    } yield ())
}
