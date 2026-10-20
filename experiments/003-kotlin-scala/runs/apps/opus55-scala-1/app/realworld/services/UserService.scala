package realworld.services

import realworld.auth.Passwords
import realworld.db.Tables.*
import realworld.db.{Db, UserRow}
import realworld.http.{ApiError, Field}
import slick.jdbc.SQLiteProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

final case class UserPatch(
    username: Field[String],
    email: Field[String],
    password: Field[String],
    bio: Field[String],
    image: Field[String]
)

final class UserService(db: Db, passwords: Passwords)(using ec: ExecutionContext) {
  import UserService.*

  def findById(id: Long): Future[Option[UserRow]] =
    db.read(users.filter(_.id === id).result.headOption)

  private def lastId = sql"SELECT last_insert_rowid()".as[Long].head

  private def takenFields(username: Option[String], email: Option[String], exceptId: Long): DBIO[Seq[String]] = {
    val byName = username.fold(DBIO.successful(false))(n => users.filter(u => u.username === n && u.id =!= exceptId).exists.result)
    val byEmail = email.fold(DBIO.successful(false))(e => users.filter(u => u.email.toLowerCase === e.toLowerCase && u.id =!= exceptId).exists.result)
    for { n <- byName; e <- byEmail } yield Seq("username" -> n, "email" -> e).collect { case (f, true) => f }
  }

  private def conflict(fields: Seq[String]): ApiError =
    ApiError(409, fields.map(_ -> Seq("has already been taken")))

  def register(username: String, email: String, password: String): Future[UserRow] = {
    val hash = passwords.hash(password)
    val action = for {
      taken <- takenFields(Some(username), Some(email), -1L)
      _ <- if (taken.nonEmpty) DBIO.failed(conflict(taken)) else unitAction
      _ <- users.map(u => (u.username, u.email, u.passwordHash)) += ((username, email, hash))
      id <- lastId
    } yield UserRow(id, username, email, hash, None, None)
    db.write(action)
  }

  def login(email: String, password: String): Future[UserRow] =
    db.read(users.filter(_.email.toLowerCase === email.toLowerCase).result.headOption).map {
      case Some(u) if passwords.verify(password, u.passwordHash) => u
      case _                                                     => throw ApiError.invalidCredentials
    }

  /** Applies a validated patch. Empty bio/image strings are stored as null. */
  def update(current: UserRow, patch: UserPatch): Future[UserRow] = {
    val newHash = patch.password.toOption.map(passwords.hash)
    def nullableValue(f: Field[String], old: Option[String]): Option[String] = f match {
      case Field.Absent   => old
      case Field.Null     => None
      case Field.Value(v) => Option(v).filter(_.trim.nonEmpty)
    }
    val action = for {
      existing <- users.filter(_.id === current.id).result.headOption
      u <- existing.fold(DBIO.failed(ApiError.tokenInvalid))(DBIO.successful)
      updated = u.copy(
        username = patch.username.toOption.getOrElse(u.username),
        email = patch.email.toOption.getOrElse(u.email),
        passwordHash = newHash.getOrElse(u.passwordHash),
        bio = nullableValue(patch.bio, u.bio),
        image = nullableValue(patch.image, u.image)
      )
      taken <- takenFields(
        Option.when(updated.username != u.username)(updated.username),
        Option.when(!updated.email.equalsIgnoreCase(u.email))(updated.email),
        u.id
      )
      _ <- if (taken.nonEmpty) DBIO.failed(conflict(taken)) else unitAction
      _ <- users.filter(_.id === u.id).update(updated)
    } yield updated
    db.write(action)
  }
}

object UserService {
  private val unitAction: DBIO[Unit] = DBIO.successful(())
}
