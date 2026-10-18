"""RealWorld (Conduit) backend API."""
import re
import secrets
import unicodedata
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any

from fastapi import Body, Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from . import security
from .db import SessionLocal, init_db
from .models import Article, ArticleTag, Comment, Favorite, Follow, User, utcnow

MIN_PASSWORD = 8


class ApiError(Exception):
    def __init__(self, status: int, errors: dict[str, list[str]]):
        self.status = status
        self.errors = errors


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.exception_handler(ApiError)
async def _api_error(_req: Request, exc: ApiError):
    return JSONResponse({"errors": exc.errors}, status_code=exc.status)


@app.exception_handler(RequestValidationError)
async def _validation_error(_req: Request, _exc: RequestValidationError):
    return JSONResponse({"errors": {"body": ["is invalid"]}}, status_code=422)


@app.exception_handler(404)
async def _not_found(_req: Request, _exc):
    return JSONResponse({"errors": {"resource": ["not found"]}}, status_code=404)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# ---------------------------------------------------------------- helpers

def iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def blank(v: Any) -> bool:
    return not isinstance(v, str) or not v.strip()


def payload(body: Any, key: str) -> dict:
    inner = body.get(key) if isinstance(body, dict) else None
    if not isinstance(inner, dict):
        raise ApiError(422, {key: ["can't be blank"]})
    return inner


def token_from(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    parts = header.split(None, 1)
    if len(parts) == 2 and parts[0].lower() in ("token", "bearer"):
        return parts[1].strip()
    return None


def optional_user(request: Request, db: Session = Depends(get_db)) -> User | None:
    token = token_from(request)
    if not token:
        return None
    uid = security.decode_token(token)
    return db.get(User, uid) if uid is not None else None


def required_user(request: Request, db: Session = Depends(get_db)) -> User:
    token = token_from(request)
    if not token:
        raise ApiError(401, {"token": ["is missing"]})
    uid = security.decode_token(token)
    user = db.get(User, uid) if uid is not None else None
    if user is None:
        raise ApiError(401, {"token": ["is invalid"]})
    return user


def user_json(user: User) -> dict:
    return {"user": {
        "email": user.email, "token": security.create_token(user.id),
        "username": user.username, "bio": user.bio, "image": user.image,
    }}


def profile_json(user: User, following: bool) -> dict:
    return {"profile": {
        "username": user.username, "bio": user.bio, "image": user.image, "following": following,
    }}


def is_following(db: Session, viewer: User | None, target_id: int) -> bool:
    if viewer is None:
        return False
    return db.get(Follow, (viewer.id, target_id)) is not None


def articles_json(db: Session, arts: list[Article], viewer: User | None) -> list[dict]:
    """Serialize articles (without body) with batched favorite/follow lookups."""
    if not arts:
        return []
    ids = [a.id for a in arts]
    counts = dict(db.execute(
        select(Favorite.article_id, func.count()).where(Favorite.article_id.in_(ids)).group_by(Favorite.article_id)
    ).all())
    fav, fol = set(), set()
    if viewer is not None:
        fav = set(db.scalars(select(Favorite.article_id).where(
            Favorite.user_id == viewer.id, Favorite.article_id.in_(ids))))
        fol = set(db.scalars(select(Follow.followee_id).where(
            Follow.follower_id == viewer.id, Follow.followee_id.in_({a.author_id for a in arts}))))
    return [{
        "slug": a.slug, "title": a.title, "description": a.description, "body": a.body,
        "tagList": [t.tag for t in a.tags],
        "createdAt": iso(a.created_at), "updatedAt": iso(a.updated_at),
        "favorited": a.id in fav, "favoritesCount": counts.get(a.id, 0),
        "author": {"username": a.author.username, "bio": a.author.bio,
                   "image": a.author.image, "following": a.author_id in fol},
    } for a in arts]


def without_body(items: list[dict]) -> list[dict]:
    return [{k: v for k, v in i.items() if k != "body"} for i in items]


def article_query():
    return select(Article).options(selectinload(Article.tags), selectinload(Article.author))


def find_article(db: Session, slug: str) -> Article:
    art = db.scalars(article_query().where(Article.slug == slug)).first()
    if art is None:
        raise ApiError(404, {"article": ["not found"]})
    return art


def slugify(title: str) -> str:
    text = unicodedata.normalize("NFKD", title).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-zA-Z0-9]+", "-", text).strip("-").lower()
    return text[:200] or "article"


def unique_slug(db: Session, title: str) -> str:
    base = slugify(title)
    if db.scalar(select(Article.id).where(Article.slug == base)) is None:
        return base
    return f"{base}-{secrets.token_hex(4)}"


def set_tags(art: Article, tags: list[str]):
    seen: list[str] = []
    for t in tags:
        if t not in seen:
            seen.append(t)
    art.tags = [ArticleTag(tag=t, position=i) for i, t in enumerate(seen)]


def clean_tags(value: Any) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(t, str) for t in value):
        raise ApiError(422, {"tagList": ["is invalid"]})
    return [t.strip() for t in value if t.strip()]


def commit(db: Session, errors: dict | None = None, status: int = 409):
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise ApiError(status, errors or {"resource": ["conflict"]})


# ---------------------------------------------------------------- users

def check_taken(db: Session, username: str | None, email: str | None, exclude: int | None = None):
    errors: dict[str, list[str]] = {}
    if username is not None:
        q = select(User.id).where(User.username == username)
        found = db.scalar(q)
        if found is not None and found != exclude:
            errors["username"] = ["has already been taken"]
    if email is not None:
        found = db.scalar(select(User.id).where(User.email == email))
        if found is not None and found != exclude:
            errors["email"] = ["has already been taken"]
    if errors:
        raise ApiError(409, errors)


@app.post("/api/users", status_code=201)
def register(body: Any = Body(default=None), db: Session = Depends(get_db)):
    data = payload(body, "user")
    errors: dict[str, list[str]] = {}
    for f in ("username", "email", "password"):
        if blank(data.get(f)):
            errors[f] = ["can't be blank"]
    if "password" not in errors and len(data["password"]) < MIN_PASSWORD:
        errors["password"] = [f"is too short (minimum is {MIN_PASSWORD} characters)"]
    if "email" not in errors and "@" not in data["email"]:
        errors["email"] = ["is invalid"]
    if errors:
        raise ApiError(422, errors)
    username, email = data["username"].strip(), data["email"].strip()
    check_taken(db, username, email)
    user = User(username=username, email=email, password_hash=security.hash_password(data["password"]))
    db.add(user)
    commit(db, {"user": ["has already been taken"]})
    return user_json(user)


@app.post("/api/users/login")
def login(body: Any = Body(default=None), db: Session = Depends(get_db)):
    data = payload(body, "user")
    errors = {f: ["can't be blank"] for f in ("email", "password") if blank(data.get(f))}
    if errors:
        raise ApiError(422, errors)
    user = db.scalars(select(User).where(User.email == data["email"].strip())).first()
    if user is None or not security.verify_password(data["password"], user.password_hash):
        raise ApiError(401, {"credentials": ["invalid"]})
    return user_json(user)


@app.get("/api/user")
def current_user(user: User = Depends(required_user)):
    return user_json(user)


@app.put("/api/user")
def update_user(body: Any = Body(default=None), user: User = Depends(required_user),
                db: Session = Depends(get_db)):
    data = payload(body, "user")
    errors: dict[str, list[str]] = {}
    for f in ("username", "email", "password"):
        if f in data and blank(data[f]):
            errors[f] = ["can't be blank"]
    if "password" in data and "password" not in errors and len(data["password"]) < MIN_PASSWORD:
        errors["password"] = [f"is too short (minimum is {MIN_PASSWORD} characters)"]
    if "email" in data and "email" not in errors and "@" not in data["email"]:
        errors["email"] = ["is invalid"]
    for f in ("bio", "image"):
        if f in data and data[f] is not None and not isinstance(data[f], str):
            errors[f] = ["is invalid"]
    if errors:
        raise ApiError(422, errors)
    new_username = data["username"].strip() if "username" in data else None
    new_email = data["email"].strip() if "email" in data else None
    check_taken(db, new_username, new_email, exclude=user.id)
    db_user = db.get(User, user.id)
    if new_username is not None:
        db_user.username = new_username
    if new_email is not None:
        db_user.email = new_email
    if "password" in data:
        db_user.password_hash = security.hash_password(data["password"])
    for f in ("bio", "image"):
        if f in data:
            setattr(db_user, f, data[f] or None)
    commit(db, {"user": ["has already been taken"]})
    return user_json(db_user)


# ---------------------------------------------------------------- profiles

def find_user(db: Session, username: str) -> User:
    user = db.scalars(select(User).where(User.username == username)).first()
    if user is None:
        raise ApiError(404, {"profile": ["not found"]})
    return user


@app.get("/api/profiles/{username}")
def get_profile(username: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    target = find_user(db, username)
    return profile_json(target, is_following(db, viewer, target.id))


@app.post("/api/profiles/{username}/follow")
def follow(username: str, viewer: User = Depends(required_user), db: Session = Depends(get_db)):
    target = find_user(db, username)
    if target.id != viewer.id and db.get(Follow, (viewer.id, target.id)) is None:
        db.add(Follow(follower_id=viewer.id, followee_id=target.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return profile_json(target, target.id != viewer.id)


@app.delete("/api/profiles/{username}/follow")
def unfollow(username: str, viewer: User = Depends(required_user), db: Session = Depends(get_db)):
    target = find_user(db, username)
    db.execute(delete(Follow).where(Follow.follower_id == viewer.id, Follow.followee_id == target.id))
    db.commit()
    return profile_json(target, False)


# ---------------------------------------------------------------- articles

def page_params(limit: int | None, offset: int | None) -> tuple[int, int]:
    limit = 20 if limit is None else max(0, min(limit, 100))
    offset = 0 if offset is None else max(0, offset)
    return limit, offset


def run_list(db: Session, conds: list, viewer: User | None, limit: int | None, offset: int | None,
             joins: list | None = None):
    limit, offset = page_params(limit, offset)
    count_q = select(func.count(func.distinct(Article.id))).select_from(Article)
    q = article_query()
    for j in joins or []:
        count_q = count_q.join(*j)
    for c in conds:
        count_q = count_q.where(c)
        q = q.where(c)
    total = db.scalar(count_q) or 0
    arts = list(db.scalars(
        q.order_by(Article.created_at.desc(), Article.id.desc()).limit(limit).offset(offset)))
    return {"articles": without_body(articles_json(db, arts, viewer)), "articlesCount": total}


@app.get("/api/articles/feed")
def feed(limit: int | None = None, offset: int | None = None,
         viewer: User = Depends(required_user), db: Session = Depends(get_db)):
    followed = select(Follow.followee_id).where(Follow.follower_id == viewer.id)
    return run_list(db, [Article.author_id.in_(followed)], viewer, limit, offset)


@app.get("/api/articles")
def list_articles(tag: str | None = None, author: str | None = None, favorited: str | None = None,
                  limit: int | None = None, offset: int | None = None,
                  viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    conds: list = []
    if tag:
        conds.append(Article.id.in_(select(ArticleTag.article_id).where(ArticleTag.tag == tag)))
    if author:
        conds.append(Article.author_id.in_(select(User.id).where(User.username == author)))
    if favorited:
        conds.append(Article.id.in_(
            select(Favorite.article_id).join(User, User.id == Favorite.user_id).where(User.username == favorited)))
    return run_list(db, conds, viewer, limit, offset)


@app.post("/api/articles", status_code=201)
def create_article(body: Any = Body(default=None), user: User = Depends(required_user),
                   db: Session = Depends(get_db)):
    data = payload(body, "article")
    errors = {f: ["can't be blank"] for f in ("title", "description", "body") if blank(data.get(f))}
    if errors:
        raise ApiError(422, errors)
    tags = clean_tags(data["tagList"]) if data.get("tagList") is not None else []
    for attempt in range(5):
        art = Article(slug=unique_slug(db, data["title"]), title=data["title"],
                      description=data["description"], body=data["body"], author_id=user.id)
        set_tags(art, tags)
        db.add(art)
        try:
            db.commit()
            break
        except IntegrityError:
            db.rollback()
    else:
        raise ApiError(409, {"slug": ["has already been taken"]})
    art = find_article(db, art.slug)
    return {"article": articles_json(db, [art], user)[0]}


@app.get("/api/articles/{slug}")
def get_article(slug: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    art = find_article(db, slug)
    return {"article": articles_json(db, [art], viewer)[0]}


@app.put("/api/articles/{slug}")
def update_article(slug: str, body: Any = Body(default=None), user: User = Depends(required_user),
                   db: Session = Depends(get_db)):
    art = find_article(db, slug)
    data = payload(body, "article")
    if art.author_id != user.id:
        raise ApiError(403, {"article": ["forbidden"]})
    errors = {f: ["can't be blank"] for f in ("title", "description", "body")
              if f in data and blank(data[f])}
    if errors:
        raise ApiError(422, errors)
    tags = None
    if "tagList" in data:
        if data["tagList"] is None:
            raise ApiError(422, {"tagList": ["can't be blank"]})
        tags = clean_tags(data["tagList"])
    for f in ("title", "description", "body"):
        if f in data:
            setattr(art, f, data[f])
    if tags is not None:
        set_tags(art, tags)
    art.updated_at = utcnow()
    commit(db)
    art = find_article(db, slug)
    return {"article": articles_json(db, [art], user)[0]}


@app.delete("/api/articles/{slug}", status_code=204)
def delete_article(slug: str, user: User = Depends(required_user), db: Session = Depends(get_db)):
    art = find_article(db, slug)
    if art.author_id != user.id:
        raise ApiError(403, {"article": ["forbidden"]})
    db.delete(art)
    db.commit()
    return Response(status_code=204)


@app.post("/api/articles/{slug}/favorite")
def favorite(slug: str, user: User = Depends(required_user), db: Session = Depends(get_db)):
    art = find_article(db, slug)
    if db.get(Favorite, (user.id, art.id)) is None:
        db.add(Favorite(user_id=user.id, article_id=art.id))
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
    return {"article": articles_json(db, [art], user)[0]}


@app.delete("/api/articles/{slug}/favorite")
def unfavorite(slug: str, user: User = Depends(required_user), db: Session = Depends(get_db)):
    art = find_article(db, slug)
    db.execute(delete(Favorite).where(Favorite.user_id == user.id, Favorite.article_id == art.id))
    db.commit()
    return {"article": articles_json(db, [art], user)[0]}


# ---------------------------------------------------------------- comments

def comment_json(db: Session, c: Comment, viewer: User | None) -> dict:
    return {
        "id": c.id, "createdAt": iso(c.created_at), "updatedAt": iso(c.updated_at), "body": c.body,
        "author": {"username": c.author.username, "bio": c.author.bio, "image": c.author.image,
                   "following": is_following(db, viewer, c.author_id)},
    }


@app.get("/api/articles/{slug}/comments")
def list_comments(slug: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    art = find_article(db, slug)
    comments = db.scalars(select(Comment).options(selectinload(Comment.author))
                          .where(Comment.article_id == art.id).order_by(Comment.id.desc()))
    return {"comments": [comment_json(db, c, viewer) for c in comments]}


@app.post("/api/articles/{slug}/comments", status_code=201)
def add_comment(slug: str, body: Any = Body(default=None), user: User = Depends(required_user),
                db: Session = Depends(get_db)):
    art = find_article(db, slug)
    data = payload(body, "comment")
    if blank(data.get("body")):
        raise ApiError(422, {"body": ["can't be blank"]})
    c = Comment(body=data["body"], article_id=art.id, author_id=user.id)
    db.add(c)
    db.commit()
    c.author = user
    return {"comment": comment_json(db, c, user)}


@app.delete("/api/articles/{slug}/comments/{comment_id}", status_code=204)
def delete_comment(slug: str, comment_id: int, user: User = Depends(required_user),
                   db: Session = Depends(get_db)):
    art = find_article(db, slug)
    c = db.get(Comment, comment_id)
    if c is None or c.article_id != art.id:
        raise ApiError(404, {"comment": ["not found"]})
    if c.author_id != user.id:
        raise ApiError(403, {"comment": ["forbidden"]})
    db.delete(c)
    db.commit()
    return Response(status_code=204)


# ---------------------------------------------------------------- tags

@app.get("/api/tags")
def tags(db: Session = Depends(get_db)):
    rows = db.scalars(select(ArticleTag.tag).group_by(ArticleTag.tag).order_by(func.count().desc(), ArticleTag.tag))
    return {"tags": list(rows)}
