from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .models import Article, ArticleTag, Comment, Tag, User, favorites, follows


def iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def user_json(user: User, token: str) -> dict:
    return {
        "user": {
            "email": user.email,
            "token": token,
            "username": user.username,
            "bio": user.bio,
            "image": user.image,
        }
    }


def profile_dict(user: User, following: bool) -> dict:
    return {"username": user.username, "bio": user.bio, "image": user.image, "following": following}


def is_following(db: Session, viewer: User | None, target: User) -> bool:
    if viewer is None:
        return False
    stmt = select(follows.c.follower_id).where(
        follows.c.follower_id == viewer.id, follows.c.followee_id == target.id
    )
    return db.execute(stmt).first() is not None


def articles_dicts(db: Session, articles: list[Article], viewer: User | None, with_body: bool) -> list[dict]:
    """Serialize articles with batched lookups for tags, favorites and follows."""
    if not articles:
        return []
    ids = [a.id for a in articles]

    tags: dict[int, list[str]] = {i: [] for i in ids}
    rows = db.execute(
        select(ArticleTag.article_id, Tag.name)
        .join(Tag, Tag.id == ArticleTag.tag_id)
        .where(ArticleTag.article_id.in_(ids))
        .order_by(ArticleTag.id)
    )
    for article_id, name in rows:
        tags[article_id].append(name)

    counts = dict(
        db.execute(
            select(favorites.c.article_id, func.count())
            .where(favorites.c.article_id.in_(ids))
            .group_by(favorites.c.article_id)
        ).all()
    )

    favorited: set[int] = set()
    following: set[int] = set()
    if viewer is not None:
        favorited = set(
            db.scalars(
                select(favorites.c.article_id).where(
                    favorites.c.user_id == viewer.id, favorites.c.article_id.in_(ids)
                )
            )
        )
        author_ids = {a.author_id for a in articles}
        following = set(
            db.scalars(
                select(follows.c.followee_id).where(
                    follows.c.follower_id == viewer.id, follows.c.followee_id.in_(author_ids)
                )
            )
        )

    result = []
    for a in articles:
        item = {
            "slug": a.slug,
            "title": a.title,
            "description": a.description,
        }
        if with_body:
            item["body"] = a.body
        item.update(
            {
                "tagList": tags[a.id],
                "createdAt": iso(a.created_at),
                "updatedAt": iso(a.updated_at),
                "favorited": a.id in favorited,
                "favoritesCount": counts.get(a.id, 0),
                "author": profile_dict(a.author, a.author_id in following),
            }
        )
        result.append(item)
    return result


def article_json(db: Session, article: Article, viewer: User | None) -> dict:
    return {"article": articles_dicts(db, [article], viewer, with_body=True)[0]}


def comment_dict(db: Session, comment: Comment, viewer: User | None) -> dict:
    return {
        "id": comment.id,
        "createdAt": iso(comment.created_at),
        "updatedAt": iso(comment.updated_at),
        "body": comment.body,
        "author": profile_dict(comment.author, is_following(db, viewer, comment.author)),
    }
