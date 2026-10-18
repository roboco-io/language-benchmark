import base64
import hashlib
import os
import re
import secrets
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt
from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .db import SessionLocal, init_db
from .models import Article, ArticleTag, Comment, Favorite, Follow, Tag, User, now

JWT_SECRET = os.environ.get("JWT_SECRET", "realworld-dev-secret-change-me")
JWT_ALGORITHM = "HS256"
TOKEN_TTL = timedelta(days=30)


@asynccontextmanager
async def lifespan(_app):
    init_db()
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


# ---------- errors ----------

class ApiError(Exception):
    def __init__(self, status: int, errors: dict[str, list[str]]):
        self.status = status
        self.errors = errors


def err(status, field, msg):
    return ApiError(status, {field: [msg]})


@app.exception_handler(ApiError)
async def _api_error(_req, exc: ApiError):
    return JSONResponse({"errors": exc.errors}, status_code=exc.status)


@app.exception_handler(RequestValidationError)
async def _validation_error(_req, _exc):
    return JSONResponse({"errors": {"body": ["is invalid"]}}, status_code=422)


@app.exception_handler(404)
async def _not_found(_req, _exc):
    return JSONResponse({"errors": {"path": ["not found"]}}, status_code=404)


# ---------- helpers ----------

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def _prehash(password: str) -> bytes:
    # Pre-hash so passwords longer than bcrypt's 72-byte limit are fully used.
    return base64.b64encode(hashlib.sha256(password.encode()).digest())


def hash_password(password: str) -> str:
    return bcrypt.hashpw(_prehash(password), bcrypt.gensalt(rounds=10)).decode()


def check_password(password: str, hashed: str) -> bool:
    return bcrypt.checkpw(_prehash(password), hashed.encode())


def make_token(user: User) -> str:
    exp = datetime.now(timezone.utc) + TOKEN_TTL
    return jwt.encode({"sub": str(user.id), "exp": exp}, JWT_SECRET, algorithm=JWT_ALGORITHM)


def _extract_token(request: Request) -> str | None:
    header = request.headers.get("authorization")
    if not header:
        return None
    parts = header.split(None, 1)
    if len(parts) == 2 and parts[0].lower() in ("token", "bearer") and parts[1].strip():
        return parts[1].strip()
    return None


def _user_from_token(request: Request, db: Session, required: bool) -> User | None:
    token = _extract_token(request)
    if token is None:
        if required:
            raise err(401, "token", "is missing")
        return None
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user = db.get(User, int(payload["sub"]))
    except (jwt.PyJWTError, KeyError, ValueError):
        user = None
    if user is None:
        raise err(401, "token", "is invalid")
    return user


def current_user(request: Request, db: Session = Depends(get_db)) -> User:
    return _user_from_token(request, db, True)


def optional_user(request: Request, db: Session = Depends(get_db)) -> User | None:
    return _user_from_token(request, db, False)


async def json_body(request: Request, key: str) -> dict:
    try:
        data = await request.json()
    except Exception:
        raise err(422, "body", "is invalid")
    inner = data.get(key) if isinstance(data, dict) else None
    if not isinstance(inner, dict):
        raise err(422, key, "can't be blank")
    return inner


def blank(v) -> bool:
    return not isinstance(v, str) or not v.strip()


def collect_blank(data: dict, fields: list[str]) -> dict[str, list[str]]:
    return {f: ["can't be blank"] for f in fields if blank(data.get(f))}


def nullable_text(v, field: str) -> str | None:
    if v is None or v == "":
        return None
    if not isinstance(v, str):
        raise err(422, field, "is invalid")
    return v


def commit(db: Session, conflict_errors=None):
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, conflict_errors or {"base": ["conflict"]})


# ---------- serializers ----------

def user_json(user: User) -> dict:
    return {"user": {
        "email": user.email, "token": make_token(user), "username": user.username,
        "bio": user.bio, "image": user.image,
    }}


def is_following(db: Session, viewer: User | None, author_id: int) -> bool:
    if viewer is None:
        return False
    return db.get(Follow, (viewer.id, author_id)) is not None


def profile_dict(db: Session, user: User, viewer: User | None) -> dict:
    return {"username": user.username, "bio": user.bio, "image": user.image,
            "following": is_following(db, viewer, user.id)}


def tag_list(db: Session, article_id: int) -> list[str]:
    rows = db.execute(
        select(Tag.name).join(ArticleTag, ArticleTag.tag_id == Tag.id)
        .where(ArticleTag.article_id == article_id).order_by(ArticleTag.id)
    ).scalars().all()
    return list(rows)


def article_dict(db: Session, a: Article, viewer: User | None, with_body: bool = True) -> dict:
    count = db.scalar(select(func.count()).select_from(Favorite).where(Favorite.article_id == a.id))
    favorited = viewer is not None and db.get(Favorite, (viewer.id, a.id)) is not None
    d = {
        "slug": a.slug, "title": a.title, "description": a.description,
        "tagList": tag_list(db, a.id),
        "createdAt": iso(a.created_at), "updatedAt": iso(a.updated_at),
        "favorited": favorited, "favoritesCount": count,
        "author": profile_dict(db, a.author, viewer),
    }
    if with_body:
        d["body"] = a.body
    return d


def comment_dict(db: Session, c: Comment, viewer: User | None) -> dict:
    return {"id": c.id, "createdAt": iso(c.created_at), "updatedAt": iso(c.updated_at),
            "body": c.body, "author": profile_dict(db, c.author, viewer)}


def slugify(title: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return s or "article"


def get_article(db: Session, slug: str) -> Article:
    a = db.scalar(select(Article).where(Article.slug == slug))
    if a is None:
        raise err(404, "article", "not found")
    return a


def set_tags(db: Session, article_id: int, names: list[str]):
    db.execute(delete(ArticleTag).where(ArticleTag.article_id == article_id))
    seen = set()
    for name in names:
        if name in seen:
            continue
        seen.add(name)
        tag = db.scalar(select(Tag).where(Tag.name == name))
        if tag is None:
            tag = Tag(name=name)
            db.add(tag)
            db.flush()
        db.add(ArticleTag(article_id=article_id, tag_id=tag.id))
    db.flush()


def parse_tags(v) -> list[str]:
    if not isinstance(v, list) or not all(isinstance(t, str) and t.strip() for t in v):
        raise err(422, "tagList", "is invalid")
    return v


def page_params(limit: int, offset: int) -> tuple[int, int]:
    return max(0, min(limit, 100)), max(0, offset)


# ---------- users ----------

@app.post("/api/users", status_code=201)
async def register(request: Request, db: Session = Depends(get_db)):
    data = await json_body(request, "user")
    errors = collect_blank(data, ["username", "email", "password"])
    if "password" not in errors and len(data["password"]) < 8:
        errors["password"] = ["is too short (minimum is 8 characters)"]
    if "email" not in errors and "@" not in data["email"]:
        errors["email"] = ["is invalid"]
    if errors:
        raise ApiError(422, errors)
    conflicts = {}
    if db.scalar(select(User.id).where(User.username == data["username"])):
        conflicts["username"] = ["has already been taken"]
    if db.scalar(select(User.id).where(User.email == data["email"])):
        conflicts["email"] = ["has already been taken"]
    if conflicts:
        raise ApiError(409, conflicts)
    user = User(username=data["username"], email=data["email"],
                password_hash=hash_password(data["password"]))
    db.add(user)
    commit(db, {"username": ["has already been taken"]})
    return JSONResponse(user_json(user), status_code=201)


@app.post("/api/users/login")
async def login(request: Request, db: Session = Depends(get_db)):
    data = await json_body(request, "user")
    errors = collect_blank(data, ["email", "password"])
    if errors:
        raise ApiError(422, errors)
    user = db.scalar(select(User).where(User.email == data["email"]))
    if user is None or not check_password(data["password"], user.password_hash):
        raise err(401, "credentials", "invalid")
    return user_json(user)


@app.get("/api/user")
def get_current_user(user: User = Depends(current_user)):
    return user_json(user)


@app.put("/api/user")
async def update_user(request: Request, user: User = Depends(current_user), db: Session = Depends(get_db)):
    data = await json_body(request, "user")
    errors: dict[str, list[str]] = {}
    for f in ("username", "email"):
        if f in data and blank(data[f]):
            errors[f] = ["can't be blank"]
    if "email" in data and "email" not in errors and "@" not in data["email"]:
        errors["email"] = ["is invalid"]
    if "password" in data:
        pw = data["password"]
        if blank(pw):
            errors["password"] = ["can't be blank"]
        elif len(pw) < 8:
            errors["password"] = ["is too short (minimum is 8 characters)"]
    bio = image = None
    try:
        if "bio" in data:
            bio = nullable_text(data["bio"], "bio")
        if "image" in data:
            image = nullable_text(data["image"], "image")
    except ApiError as e:
        errors.update(e.errors)
    if errors:
        raise ApiError(422, errors)
    conflicts = {}
    if "username" in data and data["username"] != user.username and \
            db.scalar(select(User.id).where(User.username == data["username"])):
        conflicts["username"] = ["has already been taken"]
    if "email" in data and data["email"] != user.email and \
            db.scalar(select(User.id).where(User.email == data["email"])):
        conflicts["email"] = ["has already been taken"]
    if conflicts:
        raise ApiError(409, conflicts)
    user = db.merge(user)
    if "username" in data:
        user.username = data["username"]
    if "email" in data:
        user.email = data["email"]
    if "password" in data:
        user.password_hash = hash_password(data["password"])
    if "bio" in data:
        user.bio = bio
    if "image" in data:
        user.image = image
    commit(db, {"username": ["has already been taken"]})
    return user_json(user)


# ---------- profiles ----------

def get_profile_user(db: Session, username: str) -> User:
    u = db.scalar(select(User).where(User.username == username))
    if u is None:
        raise err(404, "profile", "not found")
    return u


@app.get("/api/profiles/{username}")
def get_profile(username: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    return {"profile": profile_dict(db, get_profile_user(db, username), viewer)}


@app.post("/api/profiles/{username}/follow")
def follow(username: str, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    target = get_profile_user(db, username)
    if db.get(Follow, (viewer.id, target.id)) is None:
        db.add(Follow(follower_id=viewer.id, followed_id=target.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return {"profile": profile_dict(db, target, viewer)}


@app.delete("/api/profiles/{username}/follow")
def unfollow(username: str, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    target = get_profile_user(db, username)
    db.execute(delete(Follow).where(Follow.follower_id == viewer.id, Follow.followed_id == target.id))
    db.commit()
    return {"profile": profile_dict(db, target, viewer)}


# ---------- articles ----------

def article_page(db: Session, stmt, viewer, limit, offset) -> dict:
    limit, offset = page_params(limit, offset)
    total = db.scalar(select(func.count()).select_from(stmt.order_by(None).subquery()))
    rows = db.execute(
        stmt.order_by(Article.created_at.desc(), Article.id.desc()).limit(limit).offset(offset)
    ).scalars().unique().all()
    return {"articles": [article_dict(db, a, viewer, with_body=False) for a in rows],
            "articlesCount": total}


@app.get("/api/articles/feed")
def feed(limit: int = 20, offset: int = 0, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    stmt = select(Article).join(Follow, Follow.followed_id == Article.author_id) \
        .where(Follow.follower_id == viewer.id)
    return article_page(db, stmt, viewer, limit, offset)


@app.get("/api/articles")
def list_articles(tag: str | None = None, author: str | None = None, favorited: str | None = None,
                  limit: int = 20, offset: int = 0,
                  viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    stmt = select(Article)
    if tag:
        stmt = stmt.where(Article.id.in_(
            select(ArticleTag.article_id).join(Tag, Tag.id == ArticleTag.tag_id).where(Tag.name == tag)))
    if author:
        stmt = stmt.where(Article.author_id.in_(select(User.id).where(User.username == author)))
    if favorited:
        stmt = stmt.where(Article.id.in_(
            select(Favorite.article_id).join(User, User.id == Favorite.user_id).where(User.username == favorited)))
    return article_page(db, stmt, viewer, limit, offset)


@app.post("/api/articles", status_code=201)
async def create_article(request: Request, user: User = Depends(current_user), db: Session = Depends(get_db)):
    data = await json_body(request, "article")
    errors = collect_blank(data, ["title", "description", "body"])
    tags = []
    if "tagList" in data:
        try:
            tags = parse_tags(data["tagList"])
        except ApiError as e:
            errors.update(e.errors)
    if errors:
        raise ApiError(422, errors)
    base = slugify(data["title"])
    slug = base
    while db.scalar(select(Article.id).where(Article.slug == slug)):
        slug = f"{base}-{secrets.token_hex(3)}"
    ts = now()
    article = Article(slug=slug, title=data["title"], description=data["description"], body=data["body"],
                      author_id=user.id, created_at=ts, updated_at=ts)
    db.add(article)
    db.flush()
    set_tags(db, article.id, tags)
    commit(db)
    db.refresh(article)
    return JSONResponse({"article": article_dict(db, article, user)}, status_code=201)


@app.get("/api/articles/{slug}")
def get_one_article(slug: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    return {"article": article_dict(db, get_article(db, slug), viewer)}


@app.put("/api/articles/{slug}")
async def update_article(slug: str, request: Request, user: User = Depends(current_user),
                         db: Session = Depends(get_db)):
    article = get_article(db, slug)
    if article.author_id != user.id:
        raise err(403, "article", "forbidden")
    data = await json_body(request, "article")
    errors = {}
    for f in ("title", "description", "body"):
        if f in data and blank(data[f]):
            errors[f] = ["can't be blank"]
    tags = None
    if "tagList" in data:
        try:
            tags = parse_tags(data["tagList"])
        except ApiError as e:
            errors.update(e.errors)
    if errors:
        raise ApiError(422, errors)
    for f in ("title", "description", "body"):
        if f in data:
            setattr(article, f, data[f])
    if tags is not None:
        set_tags(db, article.id, tags)
    article.updated_at = now()
    commit(db)
    return {"article": article_dict(db, article, user)}


@app.delete("/api/articles/{slug}")
def delete_article(slug: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    if article.author_id != user.id:
        raise err(403, "article", "forbidden")
    db.execute(delete(Comment).where(Comment.article_id == article.id))
    db.execute(delete(Favorite).where(Favorite.article_id == article.id))
    db.execute(delete(ArticleTag).where(ArticleTag.article_id == article.id))
    db.delete(article)
    db.commit()
    return Response(status_code=204)


@app.post("/api/articles/{slug}/favorite")
def favorite(slug: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    if db.get(Favorite, (user.id, article.id)) is None:
        db.add(Favorite(user_id=user.id, article_id=article.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return {"article": article_dict(db, article, user)}


@app.delete("/api/articles/{slug}/favorite")
def unfavorite(slug: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    db.execute(delete(Favorite).where(Favorite.user_id == user.id, Favorite.article_id == article.id))
    db.commit()
    return {"article": article_dict(db, article, user)}


# ---------- comments ----------

@app.get("/api/articles/{slug}/comments")
def list_comments(slug: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    article = get_article(db, slug)
    rows = db.execute(select(Comment).where(Comment.article_id == article.id)
                      .order_by(Comment.created_at.desc(), Comment.id.desc())).scalars().unique().all()
    return {"comments": [comment_dict(db, c, viewer) for c in rows]}


@app.post("/api/articles/{slug}/comments", status_code=201)
async def add_comment(slug: str, request: Request, user: User = Depends(current_user),
                      db: Session = Depends(get_db)):
    article = get_article(db, slug)
    data = await json_body(request, "comment")
    errors = collect_blank(data, ["body"])
    if errors:
        raise ApiError(422, errors)
    ts = now()
    comment = Comment(article_id=article.id, author_id=user.id, body=data["body"], created_at=ts, updated_at=ts)
    db.add(comment)
    commit(db)
    db.refresh(comment)
    return JSONResponse({"comment": comment_dict(db, comment, user)}, status_code=201)


@app.delete("/api/articles/{slug}/comments/{comment_id}")
def delete_comment(slug: str, comment_id: int, user: User = Depends(current_user),
                   db: Session = Depends(get_db)):
    article = get_article(db, slug)
    comment = db.get(Comment, comment_id)
    if comment is None or comment.article_id != article.id:
        raise err(404, "comment", "not found")
    if comment.author_id != user.id:
        raise err(403, "comment", "forbidden")
    db.delete(comment)
    db.commit()
    return Response(status_code=204)


# ---------- tags ----------

@app.get("/api/tags")
def get_tags(db: Session = Depends(get_db)):
    names = db.execute(select(Tag.name).where(Tag.id.in_(select(ArticleTag.tag_id))).order_by(Tag.name)).scalars().all()
    return {"tags": list(names)}
