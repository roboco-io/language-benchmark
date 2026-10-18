"""RealWorld (Conduit) backend API built on FastAPI and SQLAlchemy."""

import re
import secrets
from contextlib import asynccontextmanager
from typing import Any, Iterator

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from starlette.exceptions import HTTPException as StarletteHTTPException

from .db import (
    Article,
    ArticleTag,
    Comment,
    Favorite,
    Follow,
    SessionLocal,
    User,
    init_db,
    utcnow,
)
from .security import create_token, decode_token, hash_password, verify_password

MIN_PASSWORD_LENGTH = 8


class ApiError(Exception):
    """An error rendered as {"errors": {field: [message]}}."""

    def __init__(self, status: int, field: str, message: str):
        self.status = status
        self.errors = {field: [message]}


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    yield


app = FastAPI(title="RealWorld API", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
)


@app.exception_handler(ApiError)
async def _api_error_handler(_request: Request, exc: ApiError):
    return JSONResponse(status_code=exc.status, content={"errors": exc.errors})


@app.exception_handler(RequestValidationError)
async def _validation_handler(_request: Request, exc: RequestValidationError):
    errors: dict[str, list[str]] = {}
    for err in exc.errors():
        field = str(err["loc"][-1]) if err.get("loc") else "request"
        errors.setdefault(field, []).append("is invalid")
    return JSONResponse(status_code=422, content={"errors": errors})


@app.exception_handler(StarletteHTTPException)
async def _http_error_handler(_request: Request, exc: StarletteHTTPException):
    message = exc.detail if isinstance(exc.detail, str) else "error"
    return JSONResponse(
        status_code=exc.status_code,
        content={"errors": {"request": [message.lower()]}},
        headers=getattr(exc, "headers", None),
    )


# --------------------------------------------------------------------------
# Dependencies
# --------------------------------------------------------------------------


def get_db() -> Iterator[Session]:
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


def _token_from_header(request: Request) -> str | None:
    header = request.headers.get("authorization")
    if not header:
        return None
    scheme, _, token = header.partition(" ")
    if scheme.lower() not in ("token", "bearer") or not token.strip():
        raise ApiError(401, "token", "is invalid")
    return token.strip()


def optional_user(request: Request, db: Session = Depends(get_db)) -> User | None:
    token = _token_from_header(request)
    if token is None:
        return None
    user_id = decode_token(token)
    user = db.get(User, user_id) if user_id is not None else None
    if user is None:
        raise ApiError(401, "token", "is invalid")
    return user


def required_user(user: User | None = Depends(optional_user)) -> User:
    if user is None:
        raise ApiError(401, "token", "is missing")
    return user


_INVALID = object()


async def json_body(request: Request) -> Any:
    """Read the JSON request body; parse errors are reported by `_unwrap`."""
    try:
        return await request.json()
    except Exception:
        return _INVALID


def _unwrap(data: Any, key: str) -> dict[str, Any]:
    """Return the object wrapped under `key` in the request body."""
    if data is _INVALID:
        raise ApiError(422, "body", "is invalid")
    if not isinstance(data, dict) or not isinstance(data.get(key), dict):
        raise ApiError(422, key, "is missing")
    return data[key]


# --------------------------------------------------------------------------
# Validation helpers
# --------------------------------------------------------------------------


def _required_str(data: dict, field: str) -> str:
    value = data.get(field)
    if value is None:
        raise ApiError(422, field, "can't be blank")
    if not isinstance(value, str):
        raise ApiError(422, field, "must be a string")
    if not value.strip():
        raise ApiError(422, field, "can't be blank")
    return value


def _nullable_str(data: dict, field: str) -> str | None:
    """Optional nullable string; an empty string is normalized to null."""
    value = data.get(field)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ApiError(422, field, "must be a string")
    return value if value.strip() else None


def _check_password(password: str) -> None:
    if len(password) < MIN_PASSWORD_LENGTH:
        raise ApiError(
            422,
            "password",
            f"is too short (minimum is {MIN_PASSWORD_LENGTH} characters)",
        )


def _tag_list(data: dict) -> list[str]:
    tags = data.get("tagList")
    if not isinstance(tags, list) or not all(isinstance(t, str) for t in tags):
        raise ApiError(422, "tagList", "must be an array of strings")
    result: list[str] = []
    for tag in tags:
        tag = tag.strip()
        if tag and tag not in result:
            result.append(tag)
    return result


def _int_param(request: Request, name: str, default: int, maximum: int | None = None) -> int:
    raw = request.query_params.get(name)
    if raw is None or raw == "":
        return default
    try:
        value = int(raw)
    except ValueError:
        raise ApiError(422, name, "must be an integer")
    if value < 0:
        raise ApiError(422, name, "must be greater than or equal to 0")
    if maximum is not None:
        value = min(value, maximum)
    return value


# --------------------------------------------------------------------------
# Serializers
# --------------------------------------------------------------------------


def _iso(dt) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def _user_json(user: User) -> dict:
    return {
        "user": {
            "email": user.email,
            "token": create_token(user.id),
            "username": user.username,
            "bio": user.bio,
            "image": user.image,
        }
    }


def _is_following(db: Session, viewer: User | None, target_id: int) -> bool:
    if viewer is None:
        return False
    return db.get(Follow, (viewer.id, target_id)) is not None


def _profile(user: User, following: bool) -> dict:
    return {
        "username": user.username,
        "bio": user.bio,
        "image": user.image,
        "following": following,
    }


def _articles_json(
    db: Session, articles: list[Article], viewer: User | None, with_body: bool
) -> list[dict]:
    """Serialize articles, loading related data in batched queries."""
    if not articles:
        return []
    ids = [a.id for a in articles]
    author_ids = {a.author_id for a in articles}

    tags: dict[int, list[str]] = {i: [] for i in ids}
    for row in db.execute(
        select(ArticleTag.article_id, ArticleTag.tag)
        .where(ArticleTag.article_id.in_(ids))
        .order_by(ArticleTag.article_id, ArticleTag.position)
    ):
        tags[row.article_id].append(row.tag)

    counts = dict(
        db.execute(
            select(Favorite.article_id, func.count())
            .where(Favorite.article_id.in_(ids))
            .group_by(Favorite.article_id)
        ).all()
    )
    authors = {
        u.id: u for u in db.scalars(select(User).where(User.id.in_(author_ids)))
    }
    favorited: set[int] = set()
    following: set[int] = set()
    if viewer is not None:
        favorited = set(
            db.scalars(
                select(Favorite.article_id).where(
                    Favorite.user_id == viewer.id, Favorite.article_id.in_(ids)
                )
            )
        )
        following = set(
            db.scalars(
                select(Follow.followee_id).where(
                    Follow.follower_id == viewer.id, Follow.followee_id.in_(author_ids)
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
                "createdAt": _iso(a.created_at),
                "updatedAt": _iso(a.updated_at),
                "favorited": a.id in favorited,
                "favoritesCount": counts.get(a.id, 0),
                "author": _profile(authors[a.author_id], a.author_id in following),
            }
        )
        result.append(item)
    return result


def _article_json(db: Session, article: Article, viewer: User | None) -> dict:
    return {"article": _articles_json(db, [article], viewer, with_body=True)[0]}


def _comment_json(db: Session, comment: Comment, viewer: User | None) -> dict:
    author = db.get(User, comment.author_id)
    return {
        "id": comment.id,
        "createdAt": _iso(comment.created_at),
        "updatedAt": _iso(comment.updated_at),
        "body": comment.body,
        "author": _profile(author, _is_following(db, viewer, author.id)),
    }


# --------------------------------------------------------------------------
# Lookups
# --------------------------------------------------------------------------


def _get_article(db: Session, slug: str) -> Article:
    article = db.scalar(select(Article).where(Article.slug == slug))
    if article is None:
        raise ApiError(404, "article", "not found")
    return article


def _get_profile_user(db: Session, username: str) -> User:
    user = db.scalar(select(User).where(User.username == username))
    if user is None:
        raise ApiError(404, "profile", "not found")
    return user


def _slugify(title: str) -> str:
    slug = re.sub(r"[^\w\s-]", "", title.lower(), flags=re.UNICODE)
    slug = re.sub(r"[\s_-]+", "-", slug).strip("-")
    return slug or "article"


def _unique_slug(db: Session, title: str, exclude_id: int | None = None) -> str:
    base = _slugify(title)
    slug = base
    while True:
        existing = db.scalar(select(Article.id).where(Article.slug == slug))
        if existing is None or existing == exclude_id:
            return slug
        slug = f"{base}-{secrets.token_hex(4)}"


def _set_tags(db: Session, article_id: int, tags: list[str]) -> None:
    db.execute(delete(ArticleTag).where(ArticleTag.article_id == article_id))
    for position, tag in enumerate(tags):
        db.add(ArticleTag(article_id=article_id, tag=tag, position=position))


# --------------------------------------------------------------------------
# Users and authentication
# --------------------------------------------------------------------------


@app.post("/api/users", status_code=201)
def register(body: Any = Depends(json_body), db: Session = Depends(get_db)):
    data = _unwrap(body, "user")
    username = _required_str(data, "username")
    email = _required_str(data, "email")
    password = _required_str(data, "password")
    _check_password(password)

    if db.scalar(select(User.id).where(User.username == username)):
        raise ApiError(409, "username", "has already been taken")
    if db.scalar(select(User.id).where(User.email == email)):
        raise ApiError(409, "email", "has already been taken")

    user = User(username=username, email=email, password_hash=hash_password(password))
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, "username", "has already been taken")
    return JSONResponse(status_code=201, content=_user_json(user))


@app.post("/api/users/login")
def login(body: Any = Depends(json_body), db: Session = Depends(get_db)):
    data = _unwrap(body, "user")
    email = _required_str(data, "email")
    password = _required_str(data, "password")
    user = db.scalar(select(User).where(User.email == email))
    if user is None or not verify_password(password, user.password_hash):
        raise ApiError(401, "credentials", "invalid")
    return _user_json(user)


@app.get("/api/user")
def current_user(user: User = Depends(required_user)):
    return _user_json(user)


@app.put("/api/user")
def update_user(
    user: User = Depends(required_user),
    body: Any = Depends(json_body),
    db: Session = Depends(get_db),
):
    data = _unwrap(body, "user")

    if "username" in data:
        username = _required_str(data, "username")
        if username != user.username and db.scalar(
            select(User.id).where(User.username == username)
        ):
            raise ApiError(409, "username", "has already been taken")
        user.username = username
    if "email" in data:
        email = _required_str(data, "email")
        if email != user.email and db.scalar(select(User.id).where(User.email == email)):
            raise ApiError(409, "email", "has already been taken")
        user.email = email
    if "password" in data:
        password = _required_str(data, "password")
        _check_password(password)
        user.password_hash = hash_password(password)
    if "bio" in data:
        user.bio = _nullable_str(data, "bio")
    if "image" in data:
        user.image = _nullable_str(data, "image")

    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, "username", "has already been taken")
    return _user_json(user)


# --------------------------------------------------------------------------
# Profiles
# --------------------------------------------------------------------------


@app.get("/api/profiles/{username}")
def get_profile(
    username: str,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    target = _get_profile_user(db, username)
    return {"profile": _profile(target, _is_following(db, viewer, target.id))}


@app.post("/api/profiles/{username}/follow")
def follow(
    username: str, viewer: User = Depends(required_user), db: Session = Depends(get_db)
):
    target = _get_profile_user(db, username)
    if target.id != viewer.id and db.get(Follow, (viewer.id, target.id)) is None:
        db.add(Follow(follower_id=viewer.id, followee_id=target.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return {"profile": _profile(target, _is_following(db, viewer, target.id))}


@app.delete("/api/profiles/{username}/follow")
def unfollow(
    username: str, viewer: User = Depends(required_user), db: Session = Depends(get_db)
):
    target = _get_profile_user(db, username)
    db.execute(
        delete(Follow).where(
            Follow.follower_id == viewer.id, Follow.followee_id == target.id
        )
    )
    db.commit()
    return {"profile": _profile(target, False)}


# --------------------------------------------------------------------------
# Articles
# --------------------------------------------------------------------------


def _paginated(db: Session, query, request: Request, viewer: User | None) -> dict:
    limit = _int_param(request, "limit", 20, maximum=1000)
    offset = _int_param(request, "offset", 0)
    total = db.scalar(select(func.count()).select_from(query.subquery()))
    articles = list(
        db.scalars(
            query.order_by(Article.created_at.desc(), Article.id.desc())
            .limit(limit)
            .offset(offset)
        )
    )
    return {
        "articles": _articles_json(db, articles, viewer, with_body=False),
        "articlesCount": total,
    }


@app.get("/api/articles")
def list_articles(
    request: Request,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    query = select(Article)
    params = request.query_params
    if params.get("tag"):
        query = query.where(
            Article.id.in_(
                select(ArticleTag.article_id).where(ArticleTag.tag == params["tag"])
            )
        )
    if params.get("author"):
        query = query.where(
            Article.author_id.in_(
                select(User.id).where(User.username == params["author"])
            )
        )
    if params.get("favorited"):
        query = query.where(
            Article.id.in_(
                select(Favorite.article_id)
                .join(User, User.id == Favorite.user_id)
                .where(User.username == params["favorited"])
            )
        )
    return _paginated(db, query, request, viewer)


@app.get("/api/articles/feed")
def feed(
    request: Request,
    viewer: User = Depends(required_user),
    db: Session = Depends(get_db),
):
    query = select(Article).where(
        Article.author_id.in_(
            select(Follow.followee_id).where(Follow.follower_id == viewer.id)
        )
    )
    return _paginated(db, query, request, viewer)


@app.post("/api/articles", status_code=201)
def create_article(
    viewer: User = Depends(required_user),
    body: Any = Depends(json_body),
    db: Session = Depends(get_db),
):
    data = _unwrap(body, "article")
    title = _required_str(data, "title")
    description = _required_str(data, "description")
    body = _required_str(data, "body")
    tags = _tag_list(data) if data.get("tagList") is not None else []

    now = utcnow()
    article = Article(
        slug=_unique_slug(db, title),
        title=title,
        description=description,
        body=body,
        author_id=viewer.id,
        created_at=now,
        updated_at=now,
    )
    db.add(article)
    db.flush()
    _set_tags(db, article.id, tags)
    db.commit()
    return JSONResponse(status_code=201, content=_article_json(db, article, viewer))


@app.get("/api/articles/{slug}")
def get_article(
    slug: str,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    return _article_json(db, _get_article(db, slug), viewer)


@app.put("/api/articles/{slug}")
def update_article(
    slug: str,
    viewer: User = Depends(required_user),
    body: Any = Depends(json_body),
    db: Session = Depends(get_db),
):
    article = _get_article(db, slug)
    if article.author_id != viewer.id:
        raise ApiError(403, "article", "forbidden")
    data = _unwrap(body, "article")

    if "title" in data:
        title = _required_str(data, "title")
        if title != article.title:
            article.slug = _unique_slug(db, title, exclude_id=article.id)
        article.title = title
    if "description" in data:
        article.description = _required_str(data, "description")
    if "body" in data:
        article.body = _required_str(data, "body")
    if "tagList" in data:
        _set_tags(db, article.id, _tag_list(data))
    article.updated_at = utcnow()
    db.commit()
    return _article_json(db, article, viewer)


@app.delete("/api/articles/{slug}", status_code=204)
def delete_article(
    slug: str, viewer: User = Depends(required_user), db: Session = Depends(get_db)
):
    article = _get_article(db, slug)
    if article.author_id != viewer.id:
        raise ApiError(403, "article", "forbidden")
    db.delete(article)
    db.commit()
    return Response(status_code=204)


# --------------------------------------------------------------------------
# Favorites
# --------------------------------------------------------------------------


@app.post("/api/articles/{slug}/favorite")
def favorite(
    slug: str, viewer: User = Depends(required_user), db: Session = Depends(get_db)
):
    article = _get_article(db, slug)
    if db.get(Favorite, (viewer.id, article.id)) is None:
        db.add(Favorite(user_id=viewer.id, article_id=article.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return _article_json(db, article, viewer)


@app.delete("/api/articles/{slug}/favorite")
def unfavorite(
    slug: str, viewer: User = Depends(required_user), db: Session = Depends(get_db)
):
    article = _get_article(db, slug)
    db.execute(
        delete(Favorite).where(
            Favorite.user_id == viewer.id, Favorite.article_id == article.id
        )
    )
    db.commit()
    return _article_json(db, article, viewer)


# --------------------------------------------------------------------------
# Comments
# --------------------------------------------------------------------------


@app.get("/api/articles/{slug}/comments")
def list_comments(
    slug: str,
    viewer: User | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    article = _get_article(db, slug)
    comments = db.scalars(
        select(Comment)
        .where(Comment.article_id == article.id)
        .order_by(Comment.created_at.desc(), Comment.id.desc())
    )
    return {"comments": [_comment_json(db, c, viewer) for c in comments]}


@app.post("/api/articles/{slug}/comments", status_code=201)
def add_comment(
    slug: str,
    viewer: User = Depends(required_user),
    body: Any = Depends(json_body),
    db: Session = Depends(get_db),
):
    article = _get_article(db, slug)
    data = _unwrap(body, "comment")
    body = _required_str(data, "body")
    now = utcnow()
    comment = Comment(
        body=body,
        article_id=article.id,
        author_id=viewer.id,
        created_at=now,
        updated_at=now,
    )
    db.add(comment)
    db.commit()
    return JSONResponse(
        status_code=201, content={"comment": _comment_json(db, comment, viewer)}
    )


@app.delete("/api/articles/{slug}/comments/{comment_id}", status_code=204)
def delete_comment(
    slug: str,
    comment_id: int,
    viewer: User = Depends(required_user),
    db: Session = Depends(get_db),
):
    article = _get_article(db, slug)
    comment = db.get(Comment, comment_id)
    if comment is None or comment.article_id != article.id:
        raise ApiError(404, "comment", "not found")
    if comment.author_id != viewer.id:
        raise ApiError(403, "comment", "forbidden")
    db.delete(comment)
    db.commit()
    return Response(status_code=204)


# --------------------------------------------------------------------------
# Tags
# --------------------------------------------------------------------------


@app.get("/api/tags")
def list_tags(db: Session = Depends(get_db)):
    rows = db.execute(
        select(ArticleTag.tag)
        .group_by(ArticleTag.tag)
        .order_by(func.count().desc(), ArticleTag.tag)
    )
    return {"tags": [r.tag for r in rows]}
