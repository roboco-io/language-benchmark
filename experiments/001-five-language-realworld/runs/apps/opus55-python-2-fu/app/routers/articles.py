import re
import secrets
from typing import Any

from fastapi import APIRouter, Body, Depends, Query, Response
from sqlalchemy import delete, func, select
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.orm import Session

from ..db import get_db
from ..errors import forbidden, not_found
from ..models import Article, ArticleTag, Comment, Tag, User, favorites, follows, utcnow
from ..security import current_user, optional_user
from ..serializers import article_json, articles_dicts, comment_dict
from ..validation import optional_required_string, present, required_string, tag_list, unwrap

router = APIRouter(prefix="/api/articles")


def slugify(title: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return slug or "article"


def unique_slug(db: Session, title: str, exclude_id: int | None = None) -> str:
    base = slugify(title)
    slug = base
    while True:
        stmt = select(Article.id).where(Article.slug == slug)
        if exclude_id is not None:
            stmt = stmt.where(Article.id != exclude_id)
        if db.execute(stmt).first() is None:
            return slug
        slug = f"{base}-{secrets.token_hex(4)}"


def set_tags(db: Session, article: Article, names: list[str]) -> None:
    db.execute(delete(ArticleTag).where(ArticleTag.article_id == article.id))
    if not names:
        return
    db.execute(insert(Tag).values([{"name": n} for n in names]).on_conflict_do_nothing())
    ids = dict(db.execute(select(Tag.name, Tag.id).where(Tag.name.in_(names))).all())
    db.add_all([ArticleTag(article_id=article.id, tag_id=ids[n]) for n in names])
    db.flush()


def get_article(db: Session, slug: str) -> Article:
    article = db.scalars(select(Article).where(Article.slug == slug)).first()
    if article is None:
        raise not_found("article")
    return article


def list_response(db: Session, stmt, viewer: User | None, limit: int, offset: int) -> dict:
    count = db.scalar(select(func.count()).select_from(stmt.subquery()))
    page = db.scalars(
        stmt.order_by(Article.created_at.desc(), Article.id.desc()).limit(limit).offset(offset)
    ).all()
    return {"articles": articles_dicts(db, list(page), viewer, with_body=False), "articlesCount": count}


@router.get("")
def list_articles(
    tag: str | None = None,
    author: str | None = None,
    favorited: str | None = None,
    limit: int = Query(20, ge=0),
    offset: int = Query(0, ge=0),
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    stmt = select(Article)
    if tag:
        stmt = stmt.where(
            Article.id.in_(
                select(ArticleTag.article_id).join(Tag, Tag.id == ArticleTag.tag_id).where(Tag.name == tag)
            )
        )
    if author:
        stmt = stmt.where(Article.author_id.in_(select(User.id).where(User.username == author)))
    if favorited:
        stmt = stmt.where(
            Article.id.in_(
                select(favorites.c.article_id)
                .join(User, User.id == favorites.c.user_id)
                .where(User.username == favorited)
            )
        )
    return list_response(db, stmt, viewer, limit, offset)


@router.get("/feed")
def feed(
    limit: int = Query(20, ge=0),
    offset: int = Query(0, ge=0),
    viewer: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    stmt = select(Article).where(
        Article.author_id.in_(select(follows.c.followee_id).where(follows.c.follower_id == viewer.id))
    )
    return list_response(db, stmt, viewer, limit, offset)


@router.post("", status_code=201)
def create_article(payload: Any = Body(...), viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    data = unwrap(payload, "article")
    title = required_string(data, "title")
    description = required_string(data, "description")
    body = required_string(data, "body")
    tags = tag_list(data, allow_null=True)
    now = utcnow()
    article = Article(
        slug=unique_slug(db, title),
        title=title,
        description=description,
        body=body,
        author_id=viewer.id,
        created_at=now,
        updated_at=now,
    )
    db.add(article)
    db.flush()
    set_tags(db, article, tags if present(tags) else [])
    return article_json(db, article, viewer)


@router.get("/{slug}")
def read_article(slug: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    return article_json(db, get_article(db, slug), viewer)


@router.put("/{slug}")
def update_article(
    slug: str, payload: Any = Body(...), viewer: User = Depends(current_user), db: Session = Depends(get_db)
):
    article = get_article(db, slug)
    if article.author_id != viewer.id:
        raise forbidden("article")
    data = unwrap(payload, "article")
    title = optional_required_string(data, "title")
    description = optional_required_string(data, "description")
    body = optional_required_string(data, "body")
    tags = tag_list(data)
    if present(title) and title != article.title:
        article.title = title
        article.slug = unique_slug(db, title, exclude_id=article.id)
    if present(description):
        article.description = description
    if present(body):
        article.body = body
    if present(tags):
        set_tags(db, article, tags)
    article.updated_at = utcnow()
    db.flush()
    return article_json(db, article, viewer)


@router.delete("/{slug}", status_code=204)
def delete_article(slug: str, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    if article.author_id != viewer.id:
        raise forbidden("article")
    db.delete(article)
    return Response(status_code=204)


@router.post("/{slug}/favorite")
def favorite(slug: str, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    db.execute(insert(favorites).values(user_id=viewer.id, article_id=article.id).on_conflict_do_nothing())
    return article_json(db, article, viewer)


@router.delete("/{slug}/favorite")
def unfavorite(slug: str, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    db.execute(delete(favorites).where(favorites.c.user_id == viewer.id, favorites.c.article_id == article.id))
    return article_json(db, article, viewer)


@router.get("/{slug}/comments")
def list_comments(slug: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    comments = db.scalars(select(Comment).where(Comment.article_id == article.id).order_by(Comment.id)).all()
    return {"comments": [comment_dict(db, c, viewer) for c in comments]}


@router.post("/{slug}/comments", status_code=201)
def add_comment(
    slug: str, payload: Any = Body(...), viewer: User = Depends(current_user), db: Session = Depends(get_db)
):
    article = get_article(db, slug)
    data = unwrap(payload, "comment")
    body = required_string(data, "body")
    now = utcnow()
    comment = Comment(body=body, article_id=article.id, author_id=viewer.id, created_at=now, updated_at=now)
    db.add(comment)
    db.flush()
    return {"comment": comment_dict(db, comment, viewer)}


@router.put("/{slug}/comments/{comment_id}")
def update_comment(
    slug: str,
    comment_id: int,
    payload: Any = Body(...),
    viewer: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    article = get_article(db, slug)
    comment = db.scalars(select(Comment).where(Comment.id == comment_id, Comment.article_id == article.id)).first()
    if comment is None:
        raise not_found("comment")
    if comment.author_id != viewer.id:
        raise forbidden("comment")
    data = unwrap(payload, "comment")
    comment.body = required_string(data, "body")
    comment.updated_at = utcnow()
    db.flush()
    return {"comment": comment_dict(db, comment, viewer)}


@router.delete("/{slug}/comments/{comment_id}", status_code=204)
def delete_comment(slug: str, comment_id: int, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    comment = db.scalars(select(Comment).where(Comment.id == comment_id, Comment.article_id == article.id)).first()
    if comment is None:
        raise not_found("comment")
    if comment.author_id != viewer.id:
        raise forbidden("comment")
    db.delete(comment)
    return Response(status_code=204)
