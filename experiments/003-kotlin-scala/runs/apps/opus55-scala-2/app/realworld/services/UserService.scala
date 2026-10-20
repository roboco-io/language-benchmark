package realworld.services

import realworld.ApiError
import realworld.db.{Tables, UserRow}
import realworld.db.Tables.*
import slick.jdbc.SQLiteProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

/** Changes requested by PUT /api/user; `None` means "leave unchanged". */
final case class UserUpdate(
    username: Option[String],
    email: Option[String],
    passwordHash: Option[String],
    bio: Option[Option[String]],
    image: Option[Option[String]]
)

final case class Profile(user: UserRow, following: Boolean)

class UserService(db: Database)(using ec: ExecutionContext):

  def findById(id: Long): Future[Option[UserRow]] =
    db.run(users.filter(_.id === id).result.headOption)

  def findByEmail(email: String): Future[Option[UserRow]] =
    db.run(users.filter(_.email === email).result.headOption)

  def register(username: String, email: String, passwordHash: String): Future[UserRow] =
    val action = for
      nameTaken  <- users.filter(_.username === username).exists.result
      emailTaken <- users.filter(_.email === email).exists.result
      _ <- conflict(nameTaken, emailTaken)
      id <- (users returning users.map(_.id)) += UserRow(0, username, email, passwordHash, None, None)
    yield UserRow(id, username, email, passwordHash, None, None)
    db.run(action.transactionally)

  def update(id: Long, change: UserUpdate): Future[UserRow] =
    val action = for
      current <- users.filter(_.id === id).result.head
      nameTaken <- change.username.filter(_ != current.username) match
        case Some(n) => users.filter(u => u.username === n && u.id =!= id).exists.result
        case None    => DBIO.successful(false)
      emailTaken <- change.email.filter(_ != current.email) match
        case Some(e) => users.filter(u => u.email === e && u.id =!= id).exists.result
        case None    => DBIO.successful(false)
      _ <- conflict(nameTaken, emailTaken)
      updated = current.copy(
        username = change.username.getOrElse(current.username),
        email = change.email.getOrElse(current.email),
        passwordHash = change.passwordHash.getOrElse(current.passwordHash),
        bio = change.bio.getOrElse(current.bio),
        image = change.image.getOrElse(current.image)
      )
      _ <- users.filter(_.id === id).update(updated)
    yield updated
    db.run(action.transactionally)

  private def conflict(nameTaken: Boolean, emailTaken: Boolean): DBIO[Unit] =
    val errors = Seq("username" -> nameTaken, "email" -> emailTaken).collect {
      case (f, true) => f -> "has already been taken"
    }
    if errors.isEmpty then DBIO.successful(()) else DBIO.failed(ApiError(409, errors))

  def profile(username: String, viewer: Option[Long]): Future[Profile] =
    val action = for
      user      <- findByUsername(username)
      following <- isFollowing(viewer, user.id)
    yield Profile(user, following)
    db.run(action)

  def follow(viewer: Long, username: String): Future[Profile] =
    val action = for
      user <- findByUsername(username)
      _    <- sqlu"INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES ($viewer, ${user.id})"
    yield Profile(user, true)
    db.run(action)

  def unfollow(viewer: Long, username: String): Future[Profile] =
    val action = for
      user <- findByUsername(username)
      _    <- follows.filter(f => f.followerId === viewer && f.followeeId === user.id).delete
    yield Profile(user, false)
    db.run(action)

  private def findByUsername(username: String): DBIO[UserRow] =
    users.filter(_.username === username).result.headOption.flatMap {
      case Some(u) => DBIO.successful(u)
      case None    => DBIO.failed(ApiError.notFound("profile"))
    }

  private def isFollowing(viewer: Option[Long], target: Long): DBIO[Boolean] = viewer match
    case Some(v) => follows.filter(f => f.followerId === v && f.followeeId === target).exists.result
    case None    => DBIO.successful(false)
