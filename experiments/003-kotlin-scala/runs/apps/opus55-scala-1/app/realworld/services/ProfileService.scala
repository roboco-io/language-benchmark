package realworld.services

import realworld.db.Tables.*
import realworld.db.{Db, UserRow}
import realworld.http.ApiError
import slick.jdbc.SQLiteProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

final class ProfileService(db: Db)(using ec: ExecutionContext) {

  private def byUsername(username: String): DBIO[UserRow] =
    users.filter(_.username === username).result.headOption.flatMap {
      case Some(u) => DBIO.successful(u)
      case None    => DBIO.failed(ApiError.notFound("profile"))
    }

  private def isFollowing(viewer: Option[Long], target: Long): DBIO[Boolean] =
    viewer.fold(DBIO.successful(false))(v => follows.filter(f => f.followerId === v && f.followeeId === target).exists.result)

  def get(username: String, viewer: Option[Long]): Future[ProfileView] =
    db.read(for {
      u <- byUsername(username)
      following <- isFollowing(viewer, u.id)
    } yield ProfileView(u.username, u.bio, u.image, following))

  def follow(username: String, viewer: Long): Future[ProfileView] =
    db.write(for {
      u <- byUsername(username)
      _ <- sqlu"INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES ($viewer, ${u.id})"
    } yield ProfileView(u.username, u.bio, u.image, following = true))

  def unfollow(username: String, viewer: Long): Future[ProfileView] =
    db.write(for {
      u <- byUsername(username)
      _ <- follows.filter(f => f.followerId === viewer && f.followeeId === u.id).delete
    } yield ProfileView(u.username, u.bio, u.image, following = false))
}
