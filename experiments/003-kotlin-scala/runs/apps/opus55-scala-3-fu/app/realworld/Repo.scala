package realworld

import slick.jdbc.GetResult
import slick.jdbc.SQLiteProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

final case class User(id: Long, username: String, email: String, passwordHash: String, bio: Option[String], image: Option[String])
final case class Profile(username: String, bio: Option[String], image: Option[String], following: Boolean)
final case class ArticleRow(id: Long, slug: String, title: String, description: String, body: String, authorId: Long, createdAt: Long, updatedAt: Long)
final case class ArticleView(row: ArticleRow, tags: Seq[String], favorited: Boolean, favoritesCount: Int, author: Profile)
final case class CommentView(id: Long, body: String, createdAt: Long, updatedAt: Long, author: Profile)

/** Fields of a user update; `None` means "not provided". */
final case class UserPatch(username: Option[String], email: Option[String], password: Option[String], bio: Option[Option[String]], image: Option[Option[String]])
final case class ArticlePatch(title: Option[String], description: Option[String], body: Option[String], tags: Option[Seq[String]])

/** All database queries. Reads use the reader pool; mutations use the single writer. */
final class Repo(db: Db)(using ec: ExecutionContext):
  private given GetResult[User] = GetResult(r => User(r.<<, r.<<, r.<<, r.<<, r.<<?, r.<<?))
  private given GetResult[ArticleRow] = GetResult(r => ArticleRow(r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<))

  private val userCols = "id, username, email, password_hash, bio, image"
  private val articleCols = "id, slug, title, description, body, author_id, created_at, updated_at"

  // ---------------------------------------------------------------- users

  private def userById(id: Long) = sql"SELECT #$userCols FROM users WHERE id = $id".as[User].headOption
  private def userByName(name: String) = sql"SELECT #$userCols FROM users WHERE username = $name".as[User].headOption
  private def userByEmail(email: String) = sql"SELECT #$userCols FROM users WHERE email = $email".as[User].headOption

  def findUser(id: Long): Future[Option[User]] = db.read(userById(id))
  def findUserByEmail(email: String): Future[Option[User]] = db.read(userByEmail(email))

  /** Inserts a user. Left contains the names of fields that are already taken. */
  def createUser(username: String, email: String, passwordHash: String): Future[Either[Seq[String], User]] =
    db.write(for
      byName <- userByName(username)
      byEmail <- userByEmail(email)
      res <-
        val taken = byName.map(_ => "username").toSeq ++ byEmail.map(_ => "email")
        if taken.nonEmpty then DBIO.successful(Left(taken))
        else
          for
            _ <- sqlu"INSERT INTO users (username, email, password_hash) VALUES ($username, $email, $passwordHash)"
            id <- sql"SELECT last_insert_rowid()".as[Long].head
          yield Right(User(id, username, email, passwordHash, None, None))
    yield res)

  def updateUser(id: Long, p: UserPatch): Future[Either[Seq[String], User]] =
    db.write(for
      current <- userById(id)
      cur = current.getOrElse(throw java.util.NoSuchElementException("user"))
      byName <- p.username.filter(_ != cur.username).fold(DBIO.successful(None))(userByName)
      byEmail <- p.email.filter(_ != cur.email).fold(DBIO.successful(None))(userByEmail)
      res <-
        val taken = byName.map(_ => "username").toSeq ++ byEmail.map(_ => "email")
        if taken.nonEmpty then DBIO.successful(Left(taken))
        else
          val u = cur.copy(
            username = p.username.getOrElse(cur.username),
            email = p.email.getOrElse(cur.email),
            passwordHash = p.password.getOrElse(cur.passwordHash),
            bio = p.bio.getOrElse(cur.bio),
            image = p.image.getOrElse(cur.image)
          )
          sqlu"""UPDATE users SET username = ${u.username}, email = ${u.email}, password_hash = ${u.passwordHash},
                 bio = ${u.bio}, image = ${u.image} WHERE id = $id""".map(_ => Right(u))
    yield res)

  // ------------------------------------------------------------- profiles

  private def isFollowing(viewer: Option[Long], target: Long): DBIO[Boolean] = viewer match
    case None => DBIO.successful(false)
    case Some(v) => sql"SELECT EXISTS(SELECT 1 FROM follows WHERE follower_id = $v AND followee_id = $target)".as[Boolean].head

  def profile(username: String, viewer: Option[Long]): Future[Option[Profile]] =
    db.read(userByName(username).flatMap {
      case None => DBIO.successful(None)
      case Some(u) => isFollowing(viewer, u.id).map(f => Some(Profile(u.username, u.bio, u.image, f)))
    })

  def setFollow(follower: Long, username: String, follow: Boolean): Future[Option[Profile]] =
    db.write(userByName(username).flatMap {
      case None => DBIO.successful(None)
      case Some(u) =>
        val change =
          if follow then sqlu"INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES ($follower, ${u.id})"
          else sqlu"DELETE FROM follows WHERE follower_id = $follower AND followee_id = ${u.id}"
        change.map(_ => Some(Profile(u.username, u.bio, u.image, follow)))
    })

  // ------------------------------------------------------------- articles

  private def articleBySlug(slug: String) = sql"SELECT #$articleCols FROM articles WHERE slug = $slug".as[ArticleRow].headOption

  def findArticle(slug: String): Future[Option[ArticleRow]] = db.read(articleBySlug(slug))

  /** Loads full article views for the given ids, preserving the order of `ids`. */
  private def views(ids: Seq[Long], viewer: Option[Long]): DBIO[Seq[ArticleView]] =
    if ids.isEmpty then DBIO.successful(Seq.empty)
    else
      val v = viewer.getOrElse(-1L)
      val in = ids.mkString(",")
      val rowsQ = sql"""
        SELECT a.id, a.slug, a.title, a.description, a.body, a.author_id, a.created_at, a.updated_at,
               u.username, u.bio, u.image,
               (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id),
               EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = $v),
               EXISTS(SELECT 1 FROM follows fo WHERE fo.followee_id = a.author_id AND fo.follower_id = $v)
        FROM articles a JOIN users u ON u.id = a.author_id
        WHERE a.id IN (#$in)""".as[(ArticleRow, Profile, Int, Boolean)](using GetResult { r =>
        val row = ArticleRow(r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<)
        val (name, bio, image) = (r.nextString(), r.nextStringOption(), r.nextStringOption())
        val count = r.nextInt()
        val fav = r.nextBoolean()
        (row, Profile(name, bio, image, r.nextBoolean()), count, fav)
      })
      val tagsQ = sql"""
        SELECT at.article_id, t.name FROM article_tags at JOIN tags t ON t.id = at.tag_id
        WHERE at.article_id IN (#$in) ORDER BY at.article_id, at.position""".as[(Long, String)]
      for
        rows <- rowsQ
        tags <- tagsQ
      yield
        val tagMap = tags.groupMap(_._1)(_._2)
        val byId = rows.map { case (row, author, count, fav) =>
          row.id -> ArticleView(row, tagMap.getOrElse(row.id, Seq.empty), fav, count, author)
        }.toMap
        ids.flatMap(byId.get)

  def article(slug: String, viewer: Option[Long]): Future[Option[ArticleView]] =
    db.read(articleBySlug(slug).flatMap {
      case None => DBIO.successful(None)
      case Some(a) => views(Seq(a.id), viewer).map(_.headOption)
    })

  /** Lists articles, newest first. Returns the page and the total count. */
  def list(tag: Option[String], author: Option[String], favorited: Option[String], limit: Int, offset: Int, viewer: Option[Long]): Future[(Seq[ArticleView], Int)] =
    val where = sql"""
      FROM articles a
      WHERE (${author} IS NULL OR a.author_id = (SELECT id FROM users WHERE username = ${author}))
        AND (${tag} IS NULL OR a.id IN (SELECT at.article_id FROM article_tags at JOIN tags t ON t.id = at.tag_id WHERE t.name = ${tag}))
        AND (${favorited} IS NULL OR a.id IN (SELECT f.article_id FROM favorites f JOIN users u ON u.id = f.user_id WHERE u.username = ${favorited}))"""
    page(where, limit, offset, viewer)

  def feed(viewer: Long, limit: Int, offset: Int): Future[(Seq[ArticleView], Int)] =
    val where = sql"""
      FROM articles a
      WHERE a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = $viewer)"""
    page(where, limit, offset, Some(viewer))

  private def page(where: slick.jdbc.SQLActionBuilder, limit: Int, offset: Int, viewer: Option[Long]) =
    db.read(for
      total <- (sql"SELECT COUNT(*) " concat where).as[Int].head
      ids <- (sql"SELECT a.id " concat where concat sql" ORDER BY a.id DESC LIMIT $limit OFFSET $offset").as[Long]
      vs <- views(ids, viewer)
    yield (vs, total))

  private def setTags(articleId: Long, tags: Seq[String]): DBIO[Unit] =
    val distinct = tags.distinct
    sqlu"DELETE FROM article_tags WHERE article_id = $articleId" >>
      DBIO.sequence(distinct.zipWithIndex.map { (name, pos) =>
        sqlu"INSERT OR IGNORE INTO tags (name) VALUES ($name)" >>
          sqlu"""INSERT INTO article_tags (article_id, tag_id, position)
                 SELECT $articleId, id, $pos FROM tags WHERE name = $name"""
      }).map(_ => ())

  private def uniqueSlug(title: String, exclude: Option[Long]): DBIO[String] =
    val base = Slug(title)
    val ex = exclude.getOrElse(-1L)
    sql"SELECT EXISTS(SELECT 1 FROM articles WHERE slug = $base AND id <> $ex)".as[Boolean].head.map { taken =>
      if taken then s"$base-${java.lang.Long.toString(System.nanoTime() & 0xffffffffL, 36)}${scala.util.Random.alphanumeric.take(4).mkString.toLowerCase}"
      else base
    }

  def createArticle(author: Long, title: String, description: String, body: String, tags: Seq[String]): Future[ArticleView] =
    val now = System.currentTimeMillis()
    db.write(for
      slug <- uniqueSlug(title, None)
      _ <- sqlu"""INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at)
                  VALUES ($slug, $title, $description, $body, $author, $now, $now)"""
      id <- sql"SELECT last_insert_rowid()".as[Long].head
      _ <- setTags(id, tags)
      v <- views(Seq(id), Some(author))
    yield v.head)

  def updateArticle(a: ArticleRow, p: ArticlePatch): Future[ArticleView] =
    // updatedAt must strictly increase, even when two updates land in the same millisecond.
    val now = math.max(System.currentTimeMillis(), a.updatedAt + 1)
    val title = p.title.getOrElse(a.title)
    val description = p.description.getOrElse(a.description)
    val body = p.body.getOrElse(a.body)
    db.write(for
      slug <- if title != a.title then uniqueSlug(title, Some(a.id)) else DBIO.successful(a.slug)
      _ <- sqlu"""UPDATE articles SET slug = $slug, title = $title, description = $description, body = $body,
                  updated_at = $now WHERE id = ${a.id}"""
      _ <- p.tags.fold(DBIO.successful(()))(setTags(a.id, _))
      v <- views(Seq(a.id), Some(a.authorId))
    yield v.head)

  def deleteArticle(id: Long): Future[Unit] =
    db.write(sqlu"DELETE FROM articles WHERE id = $id").map(_ => ())

  def setFavorite(user: Long, slug: String, favorite: Boolean): Future[Option[ArticleView]] =
    db.write(articleBySlug(slug).flatMap {
      case None => DBIO.successful(None)
      case Some(a) =>
        val change =
          if favorite then sqlu"INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES ($user, ${a.id})"
          else sqlu"DELETE FROM favorites WHERE user_id = $user AND article_id = ${a.id}"
        change >> views(Seq(a.id), Some(user)).map(_.headOption)
    })

  def tags(): Future[Seq[String]] =
    db.read(sql"SELECT t.name FROM tags t WHERE EXISTS(SELECT 1 FROM article_tags at WHERE at.tag_id = t.id) ORDER BY t.name".as[String])

  // ------------------------------------------------------------- comments

  private def commentViews(where: String, articleId: Long, viewer: Option[Long]): DBIO[Seq[CommentView]] =
    val v = viewer.getOrElse(-1L)
    sql"""
      SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image,
             EXISTS(SELECT 1 FROM follows fo WHERE fo.followee_id = c.author_id AND fo.follower_id = $v)
      FROM comments c JOIN users u ON u.id = c.author_id
      WHERE c.article_id = $articleId #$where ORDER BY c.id""".as[CommentView](using GetResult { r =>
      val (id, body, created, updated) = (r.nextLong(), r.nextString(), r.nextLong(), r.nextLong())
      CommentView(id, body, created, updated, Profile(r.nextString(), r.nextStringOption(), r.nextStringOption(), r.nextBoolean()))
    })

  def comments(articleId: Long, viewer: Option[Long]): Future[Seq[CommentView]] =
    db.read(commentViews("", articleId, viewer))

  def createComment(articleId: Long, author: Long, body: String): Future[CommentView] =
    val now = System.currentTimeMillis()
    db.write(for
      _ <- sqlu"INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES ($articleId, $author, $body, $now, $now)"
      id <- sql"SELECT last_insert_rowid()".as[Long].head
      cs <- commentViews(s"AND c.id = $id", articleId, Some(author))
    yield cs.head)

  /** Returns the comment's author id, if the comment exists on the article. */
  def commentAuthor(articleId: Long, commentId: Long): Future[Option[Long]] =
    db.read(sql"SELECT author_id FROM comments WHERE id = $commentId AND article_id = $articleId".as[Long].headOption)

  /** Replaces the comment body; `updated_at` always moves forward, even within the same millisecond. */
  def updateComment(articleId: Long, commentId: Long, body: String, viewer: Long): Future[CommentView] =
    val now = System.currentTimeMillis()
    db.write(for
      _ <- sqlu"UPDATE comments SET body = $body, updated_at = MAX($now, updated_at + 1) WHERE id = $commentId AND article_id = $articleId"
      cs <- commentViews(s"AND c.id = $commentId", articleId, Some(viewer))
    yield cs.head)

  def deleteComment(commentId: Long): Future[Unit] =
    db.write(sqlu"DELETE FROM comments WHERE id = $commentId").map(_ => ())

object Slug:
  def apply(title: String): String =
    val normalized = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
    val s = normalized.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
    if s.isEmpty then "article" else s
