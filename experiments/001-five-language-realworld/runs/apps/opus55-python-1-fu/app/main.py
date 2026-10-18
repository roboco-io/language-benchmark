"""RealWorld (Conduit) backend API."""

import re
import secrets
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from starlette.exceptions import HTTPException as StarletteHTTPException

from .db import get_db, init_schema
from .models import (
    Article,
    ArticleTag,
    Comment,
    Favorite,
    Follow,
    Tag,
    User,
    utcnow,
)
from .security import create_token, decode_token, hash_password, verify_password

MIN_PASSWORD_LENGTH = 8


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_schema()
    yield


app = FastAPI(title="RealWorld API", lifespan=lifespan)


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------


class ApiError(Exception):
    def __init__(self, status: int, field: str, message: str):
        self.status = status
        self.errors = {field: [message]}

    @classmethod
    def many(cls, status: int, errors: dict[str, list[str]]) -> "ApiError":
        exc = cls(status, "", "")
        exc.errors = errors
        return exc


class ValidationFailed(Exception):
    def __init__(self, errors: dict[str, list[str]]):
        self.errors = errors


@app.exception_handler(ApiError)
async def _api_error(_request: Request, exc: ApiError):
    return JSONResponse(status_code=exc.status, content={"errors": exc.errors})


@app.exception_handler(ValidationFailed)
async def _validation_failed(_request: Request, exc: ValidationFailed):
    return JSONResponse(status_code=422, content={"errors": exc.errors})


@app.exception_handler(RequestValidationError)
async def _request_validation(_request: Request, exc: RequestValidationError):
    errors: dict[str, list[str]] = {}
    for err in exc.errors():
        field = str(err["loc"][-1]) if err.get("loc") else "body"
        errors.setdefault(field, []).append(err.get("msg", "is invalid"))
    return JSONResponse(status_code=422, content={"errors": errors})


@app.exception_handler(StarletteHTTPException)
async def _http_error(_request: Request, exc: StarletteHTTPException):
    return JSONResponse(
        status_code=exc.status_code, content={"errors": {"body": [str(exc.detail)]}}
    )


def not_found(field: str) -> ApiError:
    return ApiError(404, field, "not found")


def forbidden(field: str) -> ApiError:
    return ApiError(403, field, "forbidden")


# ---------------------------------------------------------------------------
# Request helpers
# ---------------------------------------------------------------------------


async def json_body(request: Request) -> dict[str, Any]:
    try:
        data = await request.json()
    except Exception:
        raise ValidationFailed({"body": ["is invalid JSON"]})
    if not isinstance(data, dict):
        raise ValidationFailed({"body": ["must be a JSON object"]})
    return data


def envelope(body: dict[str, Any], key: str) -> dict[str, Any]:
    inner = body.get(key)
    if not isinstance(inner, dict):
        raise ValidationFailed({key: ["can't be blank"]})
    return inner


def is_blank(value: Any) -> bool:
    return not isinstance(value, str) or value.strip() == ""


def require_fields(data: dict[str, Any], fields: list[str]) -> None:
    errors = {f: ["can't be blank"] for f in fields if is_blank(data.get(f))}
    if errors:
        raise ValidationFailed(errors)


def password_errors(value: Any) -> list[str]:
    if is_blank(value):
        return ["can't be blank"]
    if len(value) < MIN_PASSWORD_LENGTH:
        return [f"is too short (minimum is {MIN_PASSWORD_LENGTH} characters)"]
    return []


def nullable_text(value: Any, field: str) -> str | None:
    """Optional free-text fields: null and "" both normalize to null."""
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValidationFailed({field: ["must be a string"]})
    return value if value.strip() else None


def parse_tag_list(value: Any) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(t, str) for t in value):
        raise ValidationFailed({"tagList": ["must be an array of strings"]})
    tags: list[str] = []
    for raw in value:
        tag = raw.strip()
        if tag and tag not in tags:
            tags.append(tag)
    return tags


# ---------------------------------------------------------------------------
# Auth dependencies
# ---------------------------------------------------------------------------


def _extract_token(request: Request) -> str | None:
    header = request.headers.get("Authorization")
    if not header:
        return None
    parts = header.split(None, 1)
    if len(parts) == 2 and parts[0].lower() in ("token", "bearer"):
        return parts[1].strip()
    return ""


def optional_user(request: Request, db: Session = Depends(get_db)) -> User | None:
    token = _extract_token(request)
    if token is None:
        return None
    user_id = decode_token(token) if token else None
    user = db.get(User, user_id) if user_id is not None else None
    if user is None:
        raise ApiError(401, "token", "is invalid")
    return user


def current_user(user: User | None = Depends(optional_user)) -> User:
    if user is None:
        raise ApiError(401, "token", "is missing")
    return user


# ---------------------------------------------------------------------------
# Serialization
# ---------------------------------------------------------------------------


def iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def user_json(user: User) -> dict[str, Any]:
    return {
        "user": {
            "email": user.email,
            "token": create_token(user.id),
            "username": user.username,
            "bio": user.bio,
            "image": user.image,
        }
    }


def profile_dict(user: User, following: bool) -> dict[str, Any]:
    return {
        "username": user.username,
        "bio": user.bio,
        "image": user.image,
        "following": following,
    }


def following_ids(db: Session, viewer: User | None, author_ids: set[int]) -> set[int]:
    if viewer is None or not author_ids:
        return set()
    rows = db.execute(
        select(Follow.followee_id).where(
            Follow.follower_id == viewer.id, Follow.followee_id.in_(author_ids)
        )
    )
    return {r[0] for r in rows}


def serialize_articles(
    db: Session, articles: list[Article], viewer: User | None, include_body: bool
) -> list[dict[str, Any]]:
    if not articles:
        return []
    ids = [a.id for a in articles]
    author_ids = {a.author_id for a in articles}

    authors = {
        u.id: u for u in db.scalars(select(User).where(User.id.in_(author_ids)))
    }
    tags: dict[int, list[str]] = {i: [] for i in ids}
    for article_id, name in db.execute(
        select(ArticleTag.article_id, Tag.name)
        .join(Tag, Tag.id == ArticleTag.tag_id)
        .where(ArticleTag.article_id.in_(ids))
        .order_by(ArticleTag.article_id, ArticleTag.position)
    ):
        tags[article_id].append(name)
    counts = dict(
        db.execute(
            select(Favorite.article_id, func.count())
            .where(Favorite.article_id.in_(ids))
            .group_by(Favorite.article_id)
        ).all()
    )
    favorited: set[int] = set()
    if viewer is not None:
        favorited = {
            r[0]
            for r in db.execute(
                select(Favorite.article_id).where(
                    Favorite.user_id == viewer.id, Favorite.article_id.in_(ids)
                )
            )
        }
    followed = following_ids(db, viewer, author_ids)

    out = []
    for a in articles:
        item: dict[str, Any] = {
            "slug": a.slug,
            "title": a.title,
            "description": a.description,
        }
        if include_body:
            item["body"] = a.body
        item.update(
            {
                "tagList": tags[a.id],
                "createdAt": iso(a.created_at),
                "updatedAt": iso(a.updated_at),
                "favorited": a.id in favorited,
                "favoritesCount": counts.get(a.id, 0),
                "author": profile_dict(
                    authors[a.author_id], a.author_id in followed
                ),
            }
        )
        out.append(item)
    return out


def article_json(db: Session, article: Article, viewer: User | None) -> dict[str, Any]:
    return {"article": serialize_articles(db, [article], viewer, True)[0]}


def comment_dict(comment: Comment, author: User, following: bool) -> dict[str, Any]:
    return {
        "id": comment.id,
        "createdAt": iso(comment.created_at),
        "updatedAt": iso(comment.updated_at),
        "body": comment.body,
        "author": profile_dict(author, following),
    }


# ---------------------------------------------------------------------------
# Lookups
# ---------------------------------------------------------------------------


def get_article(db: Session, slug: str) -> Article:
    article = db.scalar(select(Article).where(Article.slug == slug))
    if article is None:
        raise not_found("article")
    return article


def get_profile_user(db: Session, username: str) -> User:
    user = db.scalar(select(User).where(User.username == username))
    if user is None:
        raise not_found("profile")
    return user


def is_following(db: Session, viewer: User | None, target: User) -> bool:
    return bool(following_ids(db, viewer, {target.id}))


# ---------------------------------------------------------------------------
# Users
# ---------------------------------------------------------------------------


def check_unique(db: Session, username: str | None, email: str | None, exclude_id=None):
    errors: dict[str, list[str]] = {}
    for field, value in (("username", username), ("email", email)):
        if value is None:
            continue
        stmt = select(User.id).where(getattr(User, field) == value)
        if exclude_id is not None:
            stmt = stmt.where(User.id != exclude_id)
        if db.scalar(stmt) is not None:
            errors[field] = ["has already been taken"]
    if errors:
        raise ApiError.many(409, errors)


def validate_email(email: str) -> None:
    if not re.fullmatch(r"[^@\s]+@[^@\s]+", email):
        raise ValidationFailed({"email": ["is invalid"]})


@app.post("/api/users", status_code=201)
def register(body: dict = Depends(json_body), db: Session = Depends(get_db)):
    data = envelope(body, "user")
    require_fields(data, ["username", "email"])
    pw_errors = password_errors(data.get("password"))
    if pw_errors:
        raise ValidationFailed({"password": pw_errors})
    username = data["username"].strip()
    email = data["email"].strip()
    validate_email(email)
    check_unique(db, username, email)
    user = User(
        username=username, email=email, password_hash=hash_password(data["password"])
    )
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        check_unique(db, username, email)
        raise
    return JSONResponse(status_code=201, content=user_json(user))


@app.post("/api/users/login")
def login(body: dict = Depends(json_body), db: Session = Depends(get_db)):
    data = envelope(body, "user")
    require_fields(data, ["email", "password"])
    user = db.scalar(select(User).where(User.email == data["email"].strip()))
    if user is None or not verify_password(data["password"], user.password_hash):
        raise ApiError(401, "credentials", "invalid")
    return user_json(user)


@app.get("/api/user")
def get_current_user(user: User = Depends(current_user)):
    return user_json(user)


@app.put("/api/user")
def update_user(
    body: dict = Depends(json_body),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    data = envelope(body, "user")
    errors: dict[str, list[str]] = {}
    for field in ("username", "email"):
        if field in data and is_blank(data[field]):
            errors[field] = ["can't be blank"]
    if "password" in data:
        pw_errors = password_errors(data["password"])
        if pw_errors:
            errors["password"] = pw_errors
    if errors:
        raise ValidationFailed(errors)

    username = data["username"].strip() if "username" in data else None
    email = data["email"].strip() if "email" in data else None
    if email is not None:
        validate_email(email)
    check_unique(db, username, email, exclude_id=user.id)

    if username is not None:
        user.username = username
    if email is not None:
        user.email = email
    if "password" in data:
        user.password_hash = hash_password(data["password"])
    if "bio" in data:
        user.bio = nullable_text(data["bio"], "bio")
    if "image" in data:
        user.image = nullable_text(data["image"], "image")
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        check_unique(db, username, email, exclude_id=user.id)
        raise
    return user_json(user)


# ---------------------------------------------------------------------------
# Profiles
# ---------------------------------------------------------------------------


@app.get("/api/profiles/{username}")
def get_profile(
    username: str,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    target = get_profile_user(db, username)
    return {"profile": profile_dict(target, is_following(db, viewer, target))}


@app.post("/api/profiles/{username}/follow")
def follow(
    username: str, user: User = Depends(current_user), db: Session = Depends(get_db)
):
    target = get_profile_user(db, username)
    if target.id != user.id and not is_following(db, user, target):
        db.add(Follow(follower_id=user.id, followee_id=target.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return {"profile": profile_dict(target, is_following(db, user, target))}


@app.delete("/api/profiles/{username}/follow")
def unfollow(
    username: str, user: User = Depends(current_user), db: Session = Depends(get_db)
):
    target = get_profile_user(db, username)
    db.execute(
        delete(Follow).where(
            Follow.follower_id == user.id, Follow.followee_id == target.id
        )
    )
    db.commit()
    return {"profile": profile_dict(target, False)}


# ---------------------------------------------------------------------------
# Articles
# ---------------------------------------------------------------------------


def parse_paging(request: Request) -> tuple[int, int]:
    def as_int(name: str, default: int) -> int:
        raw = request.query_params.get(name)
        if raw is None or raw == "":
            return default
        try:
            value = int(raw)
        except ValueError:
            raise ValidationFailed({name: ["must be an integer"]})
        if value < 0:
            raise ValidationFailed({name: ["must be non-negative"]})
        return value

    return as_int("limit", 20), as_int("offset", 0)


def article_page(db: Session, stmt, request: Request, viewer: User | None):
    limit, offset = parse_paging(request)
    total = db.scalar(select(func.count()).select_from(stmt.subquery()))
    articles = list(
        db.scalars(
            stmt.order_by(Article.created_at.desc(), Article.id.desc())
            .limit(limit)
            .offset(offset)
        )
    )
    return {
        "articles": serialize_articles(db, articles, viewer, include_body=False),
        "articlesCount": total,
    }


@app.get("/api/articles")
def list_articles(
    request: Request,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    params = request.query_params
    stmt = select(Article)
    if tag := params.get("tag"):
        stmt = stmt.where(
            Article.id.in_(
                select(ArticleTag.article_id)
                .join(Tag, Tag.id == ArticleTag.tag_id)
                .where(Tag.name == tag)
            )
        )
    if author := params.get("author"):
        stmt = stmt.where(
            Article.author_id.in_(select(User.id).where(User.username == author))
        )
    if favorited := params.get("favorited"):
        stmt = stmt.where(
            Article.id.in_(
                select(Favorite.article_id)
                .join(User, User.id == Favorite.user_id)
                .where(User.username == favorited)
            )
        )
    return article_page(db, stmt, request, viewer)


@app.get("/api/articles/feed")
def feed(
    request: Request, user: User = Depends(current_user), db: Session = Depends(get_db)
):
    stmt = select(Article).where(
        Article.author_id.in_(
            select(Follow.followee_id).where(Follow.follower_id == user.id)
        )
    )
    return article_page(db, stmt, request, user)


def slugify(title: str) -> str:
    slug = re.sub(r"[^\w]+", "-", title.lower(), flags=re.UNICODE).strip("-_")
    return slug or "article"


def unique_slug(db: Session, title: str, exclude_id: int | None = None) -> str:
    base = slugify(title)
    slug = base
    while True:
        stmt = select(Article.id).where(Article.slug == slug)
        if exclude_id is not None:
            stmt = stmt.where(Article.id != exclude_id)
        if db.scalar(stmt) is None:
            return slug
        slug = f"{base}-{secrets.token_hex(4)}"


def set_tags(db: Session, article: Article, names: list[str]) -> None:
    db.execute(delete(ArticleTag).where(ArticleTag.article_id == article.id))
    for position, name in enumerate(names):
        tag = db.scalar(select(Tag).where(Tag.name == name))
        if tag is None:
            tag = Tag(name=name)
            db.add(tag)
            db.flush()
        db.add(ArticleTag(article_id=article.id, tag_id=tag.id, position=position))


@app.post("/api/articles", status_code=201)
def create_article(
    body: dict = Depends(json_body),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    data = envelope(body, "article")
    require_fields(data, ["title", "description", "body"])
    tags = parse_tag_list(data["tagList"]) if data.get("tagList") is not None else []
    now = utcnow()
    article = Article(
        slug=unique_slug(db, data["title"]),
        title=data["title"],
        description=data["description"],
        body=data["body"],
        author_id=user.id,
        created_at=now,
        updated_at=now,
    )
    db.add(article)
    db.flush()
    set_tags(db, article, tags)
    db.commit()
    return JSONResponse(status_code=201, content=article_json(db, article, user))


@app.get("/api/articles/{slug}")
def read_article(
    slug: str,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    return article_json(db, get_article(db, slug), viewer)


@app.put("/api/articles/{slug}")
def update_article(
    slug: str,
    body: dict = Depends(json_body),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    article = get_article(db, slug)
    if article.author_id != user.id:
        raise forbidden("article")
    data = envelope(body, "article")
    errors = {
        f: ["can't be blank"]
        for f in ("title", "description", "body")
        if f in data and is_blank(data[f])
    }
    if errors:
        raise ValidationFailed(errors)
    tags = parse_tag_list(data["tagList"]) if "tagList" in data else None

    if "title" in data and data["title"] != article.title:
        article.title = data["title"]
        article.slug = unique_slug(db, data["title"], exclude_id=article.id)
    if "description" in data:
        article.description = data["description"]
    if "body" in data:
        article.body = data["body"]
    if tags is not None:
        set_tags(db, article, tags)
    article.updated_at = utcnow()
    db.commit()
    return article_json(db, article, user)


@app.delete("/api/articles/{slug}", status_code=204)
def delete_article(
    slug: str, user: User = Depends(current_user), db: Session = Depends(get_db)
):
    article = get_article(db, slug)
    if article.author_id != user.id:
        raise forbidden("article")
    db.execute(delete(Comment).where(Comment.article_id == article.id))
    db.execute(delete(Favorite).where(Favorite.article_id == article.id))
    db.execute(delete(ArticleTag).where(ArticleTag.article_id == article.id))
    db.delete(article)
    db.commit()
    return Response(status_code=204)


# ---------------------------------------------------------------------------
# Favorites
# ---------------------------------------------------------------------------


@app.post("/api/articles/{slug}/favorite")
def favorite(
    slug: str, user: User = Depends(current_user), db: Session = Depends(get_db)
):
    article = get_article(db, slug)
    if db.get(Favorite, (user.id, article.id)) is None:
        db.add(Favorite(user_id=user.id, article_id=article.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return article_json(db, article, user)


@app.delete("/api/articles/{slug}/favorite")
def unfavorite(
    slug: str, user: User = Depends(current_user), db: Session = Depends(get_db)
):
    article = get_article(db, slug)
    db.execute(
        delete(Favorite).where(
            Favorite.user_id == user.id, Favorite.article_id == article.id
        )
    )
    db.commit()
    return article_json(db, article, user)


# ---------------------------------------------------------------------------
# Comments
# ---------------------------------------------------------------------------


@app.post("/api/articles/{slug}/comments", status_code=201)
def add_comment(
    slug: str,
    body: dict = Depends(json_body),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    article = get_article(db, slug)
    data = envelope(body, "comment")
    require_fields(data, ["body"])
    now = utcnow()
    comment = Comment(
        body=data["body"],
        article_id=article.id,
        author_id=user.id,
        created_at=now,
        updated_at=now,
    )
    db.add(comment)
    db.commit()
    return JSONResponse(
        status_code=201, content={"comment": comment_dict(comment, user, False)}
    )


@app.get("/api/articles/{slug}/comments")
def list_comments(
    slug: str,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    article = get_article(db, slug)
    rows = db.execute(
        select(Comment, User)
        .join(User, User.id == Comment.author_id)
        .where(Comment.article_id == article.id)
        .order_by(Comment.created_at.desc(), Comment.id.desc())
    ).all()
    followed = following_ids(db, viewer, {u.id for _, u in rows})
    return {"comments": [comment_dict(c, u, u.id in followed) for c, u in rows]}


def get_own_comment(db: Session, slug: str, comment_id: str, user: User) -> Comment:
    """Look up a comment on an article and ensure `user` authored it."""
    article = get_article(db, slug)
    comment = None
    if comment_id.isdigit():
        comment = db.scalar(
            select(Comment).where(
                Comment.id == int(comment_id), Comment.article_id == article.id
            )
        )
    if comment is None:
        raise not_found("comment")
    if comment.author_id != user.id:
        raise forbidden("comment")
    return comment


@app.put("/api/articles/{slug}/comments/{comment_id}")
def update_comment(
    slug: str,
    comment_id: str,
    user: User = Depends(current_user),
    body: dict = Depends(json_body),
    db: Session = Depends(get_db),
):
    comment = get_own_comment(db, slug, comment_id, user)
    data = envelope(body, "comment")
    require_fields(data, ["body"])
    comment.body = data["body"]
    comment.updated_at = utcnow()
    db.commit()
    return {"comment": comment_dict(comment, user, False)}


@app.delete("/api/articles/{slug}/comments/{comment_id}", status_code=204)
def delete_comment(
    slug: str,
    comment_id: str,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    comment = get_own_comment(db, slug, comment_id, user)
    db.delete(comment)
    db.commit()
    return Response(status_code=204)


# ---------------------------------------------------------------------------
# Tags
# ---------------------------------------------------------------------------


@app.get("/api/tags")
def list_tags(db: Session = Depends(get_db)):
    rows = db.execute(
        select(Tag.name)
        .join(ArticleTag, ArticleTag.tag_id == Tag.id)
        .group_by(Tag.id)
        .order_by(func.count().desc(), Tag.name)
    )
    return {"tags": [r[0] for r in rows]}
