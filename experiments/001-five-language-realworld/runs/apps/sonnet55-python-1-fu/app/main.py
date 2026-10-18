import fcntl
import re
import secrets
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from sqlalchemy import delete, func, insert, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from . import security
from .db import DATABASE_PATH, Base, SessionLocal, engine
from .models import Article, Comment, Tag, User, article_tags, favorites, follows, utcnow

MIN_PASSWORD = 8


class ApiError(Exception):
    def __init__(self, status: int, errors: dict):
        self.status = status
        self.errors = errors


def err(status: int, field: str, msg: str) -> ApiError:
    return ApiError(status, {field: [msg]})


def init_schema():
    """Create tables; a file lock serializes concurrent workers starting together."""
    with open(f"{DATABASE_PATH}.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            Base.metadata.create_all(engine)
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


@asynccontextmanager
async def lifespan(_app):
    init_schema()
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.exception_handler(ApiError)
async def api_error_handler(_req, exc: ApiError):
    return JSONResponse({"errors": exc.errors}, status_code=exc.status)


@app.exception_handler(HTTPException)
async def http_error_handler(_req, exc: HTTPException):
    return JSONResponse({"errors": {"request": [str(exc.detail)]}}, status_code=exc.status_code)


@app.exception_handler(RequestValidationError)
async def validation_handler(_req, exc):
    return JSONResponse({"errors": {"body": ["is invalid"]}}, status_code=422)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# ---------- auth helpers ----------

def _token_from(request: Request):
    header = request.headers.get("authorization", "")
    parts = header.split(None, 1)
    if len(parts) == 2 and parts[0].lower() in ("token", "bearer") and parts[1].strip():
        return parts[1].strip()
    return None


def optional_user(request: Request, db: Session = Depends(get_db)):
    token = _token_from(request)
    if token is None:
        return None
    uid = security.decode_token(token)
    user = db.get(User, uid) if uid is not None else None
    if user is None:
        raise err(401, "token", "is invalid")
    return user


def current_user(request: Request, db: Session = Depends(get_db)):
    if _token_from(request) is None:
        raise err(401, "token", "is missing")
    return optional_user(request, db)


async def read_object(request: Request, key: str) -> dict:
    try:
        data = await request.json()
    except Exception:
        raise err(422, "body", "is invalid")
    obj = data.get(key) if isinstance(data, dict) else None
    if not isinstance(obj, dict):
        raise err(422, key, "can't be blank")
    return obj


def blank(v) -> bool:
    return not isinstance(v, str) or not v.strip()


# ---------- serializers ----------

def user_json(user: User) -> dict:
    return {
        "user": {
            "email": user.email,
            "token": security.create_token(user.id),
            "username": user.username,
            "bio": user.bio,
            "image": user.image,
        }
    }


def profile_dict(user: User, following: bool) -> dict:
    return {
        "username": user.username,
        "bio": user.bio,
        "image": user.image,
        "following": following,
    }


def iso(dt) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def articles_json(db: Session, articles: list, viewer, with_body: bool = True) -> list:
    if not articles:
        return []
    ids = [a.id for a in articles]
    counts = dict(
        db.execute(
            select(favorites.c.article_id, func.count()).where(favorites.c.article_id.in_(ids)).group_by(favorites.c.article_id)
        ).all()
    )
    faved, followed = set(), set()
    if viewer is not None:
        faved = set(
            db.execute(
                select(favorites.c.article_id).where(favorites.c.user_id == viewer.id, favorites.c.article_id.in_(ids))
            ).scalars()
        )
        followed = set(db.execute(select(follows.c.followed_id).where(follows.c.follower_id == viewer.id)).scalars())
    out = [
        {
            "slug": a.slug,
            "title": a.title,
            "description": a.description,
            "body": a.body,
            "tagList": [t.name for t in a.tags],
            "createdAt": iso(a.created_at),
            "updatedAt": iso(a.updated_at),
            "favorited": a.id in faved,
            "favoritesCount": counts.get(a.id, 0),
            "author": profile_dict(a.author, a.author_id in followed),
        }
        for a in articles
    ]
    if not with_body:
        for item in out:
            del item["body"]
    return out


def article_json(db: Session, article: Article, viewer) -> dict:
    return {"article": articles_json(db, [article], viewer)[0]}


def is_following(db: Session, viewer, target: User) -> bool:
    if viewer is None:
        return False
    return (
        db.execute(
            select(follows.c.followed_id).where(follows.c.follower_id == viewer.id, follows.c.followed_id == target.id)
        ).first()
        is not None
    )


# ---------- users ----------

def validate_password(password, errors: dict):
    if blank(password) if not isinstance(password, str) else password == "":
        errors.setdefault("password", []).append("can't be blank")
    elif len(password) < MIN_PASSWORD:
        errors.setdefault("password", []).append(f"is too short (minimum is {MIN_PASSWORD} characters)")


def validate_email(email, errors: dict):
    if blank(email):
        errors.setdefault("email", []).append("can't be blank")
    elif not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email.strip()):
        errors.setdefault("email", []).append("is invalid")


def taken_errors(db: Session, username=None, email=None, exclude_id=None) -> dict:
    errors = {}
    if username is not None:
        q = select(User.id).where(User.username == username)
        if exclude_id:
            q = q.where(User.id != exclude_id)
        if db.execute(q).first():
            errors["username"] = ["has already been taken"]
    if email is not None:
        q = select(User.id).where(User.email == email)
        if exclude_id:
            q = q.where(User.id != exclude_id)
        if db.execute(q).first():
            errors["email"] = ["has already been taken"]
    return errors


@app.post("/api/users", status_code=201)
async def register(request: Request, db: Session = Depends(get_db)):
    data = await read_object(request, "user")
    errors = {}
    username, email, password = data.get("username"), data.get("email"), data.get("password")
    if blank(username):
        errors["username"] = ["can't be blank"]
    validate_email(email, errors)
    validate_password(password, errors)
    if errors:
        raise ApiError(422, errors)
    username, email = username.strip(), email.strip()
    dup = taken_errors(db, username, email)
    if dup:
        raise ApiError(409, dup)
    user = User(username=username, email=email, password_hash=security.hash_password(password))
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, taken_errors(db, username, email) or {"user": ["has already been taken"]})
    return user_json(user)


@app.post("/api/users/login")
async def login(request: Request, db: Session = Depends(get_db)):
    data = await read_object(request, "user")
    errors = {}
    email, password = data.get("email"), data.get("password")
    if blank(email):
        errors["email"] = ["can't be blank"]
    if not isinstance(password, str) or password == "":
        errors["password"] = ["can't be blank"]
    if errors:
        raise ApiError(422, errors)
    user = db.execute(select(User).where(User.email == email.strip())).scalar_one_or_none()
    if user is None or not security.verify_password(password, user.password_hash):
        raise err(401, "credentials", "invalid")
    return user_json(user)


@app.get("/api/user")
def get_current_user(user: User = Depends(current_user)):
    return user_json(user)


@app.put("/api/user")
async def update_user(request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
    data = await read_object(request, "user")
    errors = {}
    if "username" in data and blank(data["username"]):
        errors["username"] = ["can't be blank"]
    if "email" in data:
        validate_email(data["email"], errors)
    if "password" in data:
        validate_password(data["password"], errors)
    for field in ("bio", "image"):
        if field in data and data[field] is not None and not isinstance(data[field], str):
            errors[field] = ["is invalid"]
    if errors:
        raise ApiError(422, errors)
    new_username = data["username"].strip() if "username" in data else None
    new_email = data["email"].strip() if "email" in data else None
    dup = taken_errors(
        db,
        new_username if new_username != user.username else None,
        new_email if new_email != user.email else None,
        exclude_id=user.id,
    )
    if dup:
        raise ApiError(409, dup)
    if new_username is not None:
        user.username = new_username
    if new_email is not None:
        user.email = new_email
    if "password" in data:
        user.password_hash = security.hash_password(data["password"])
    if "bio" in data:
        user.bio = data["bio"] or None
    if "image" in data:
        user.image = data["image"] or None
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, {"user": ["has already been taken"]})
    return user_json(user)


# ---------- profiles ----------

def find_profile(db: Session, username: str) -> User:
    user = db.execute(select(User).where(User.username == username)).scalar_one_or_none()
    if user is None:
        raise err(404, "profile", "not found")
    return user


@app.get("/api/profiles/{username}")
def get_profile(username: str, db: Session = Depends(get_db), viewer=Depends(optional_user)):
    target = find_profile(db, username)
    return {"profile": profile_dict(target, is_following(db, viewer, target))}


@app.post("/api/profiles/{username}/follow")
def follow(username: str, db: Session = Depends(get_db), viewer: User = Depends(current_user)):
    target = find_profile(db, username)
    if target.id != viewer.id and not is_following(db, viewer, target):
        db.execute(insert(follows).values(follower_id=viewer.id, followed_id=target.id))
        db.commit()
    return {"profile": profile_dict(target, True)}


@app.delete("/api/profiles/{username}/follow")
def unfollow(username: str, db: Session = Depends(get_db), viewer: User = Depends(current_user)):
    target = find_profile(db, username)
    db.execute(delete(follows).where(follows.c.follower_id == viewer.id, follows.c.followed_id == target.id))
    db.commit()
    return {"profile": profile_dict(target, False)}


# ---------- articles ----------

def slugify(title: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return s or "article"


def find_article(db: Session, slug: str) -> Article:
    article = db.execute(
        select(Article).where(Article.slug == slug).options(selectinload(Article.tags))
    ).scalar_one_or_none()
    if article is None:
        raise err(404, "article", "not found")
    return article


def resolve_tags(db: Session, names: list) -> list:
    unique = list(dict.fromkeys(n.strip() for n in names))
    if not unique:
        return []
    existing = {t.name: t for t in db.execute(select(Tag).where(Tag.name.in_(unique))).scalars()}
    tags = []
    for name in unique:
        tag = existing.get(name)
        if tag is None:
            tag = Tag(name=name)
            db.add(tag)
        tags.append(tag)
    return tags


def validate_tag_list(value, errors: dict):
    if not isinstance(value, list) or any(not isinstance(t, str) or not t.strip() for t in value):
        errors["tagList"] = ["is invalid"]


def listing(db: Session, query, limit: int, offset: int, viewer) -> dict:
    total = db.execute(select(func.count()).select_from(query.order_by(None).subquery())).scalar_one()
    rows = (
        db.execute(
            query.order_by(Article.created_at.desc(), Article.id.desc())
            .limit(limit)
            .offset(offset)
            .options(selectinload(Article.tags), selectinload(Article.author))
        )
        .scalars()
        .all()
    )
    return {"articles": articles_json(db, rows, viewer, with_body=False), "articlesCount": total}


def paging(request: Request):
    def num(name, default):
        try:
            return max(0, int(request.query_params.get(name, default)))
        except ValueError:
            return default
    return num("limit", 20), num("offset", 0)


@app.get("/api/articles/feed")
def feed(request: Request, db: Session = Depends(get_db), viewer: User = Depends(current_user)):
    limit, offset = paging(request)
    q = select(Article).where(
        Article.author_id.in_(select(follows.c.followed_id).where(follows.c.follower_id == viewer.id))
    )
    return listing(db, q, limit, offset, viewer)


@app.get("/api/articles")
def list_articles(request: Request, db: Session = Depends(get_db), viewer=Depends(optional_user)):
    limit, offset = paging(request)
    params = request.query_params
    q = select(Article)
    if params.get("tag"):
        q = q.where(
            Article.id.in_(
                select(article_tags.c.article_id).join(Tag, Tag.id == article_tags.c.tag_id).where(Tag.name == params["tag"])
            )
        )
    if params.get("author"):
        q = q.where(Article.author_id.in_(select(User.id).where(User.username == params["author"])))
    if params.get("favorited"):
        q = q.where(
            Article.id.in_(
                select(favorites.c.article_id)
                .join(User, User.id == favorites.c.user_id)
                .where(User.username == params["favorited"])
            )
        )
    return listing(db, q, limit, offset, viewer)


@app.post("/api/articles", status_code=201)
async def create_article(request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
    data = await read_object(request, "article")
    errors = {}
    for field in ("title", "description", "body"):
        if blank(data.get(field)):
            errors[field] = ["can't be blank"]
    if "tagList" in data:
        validate_tag_list(data["tagList"], errors)
    if errors:
        raise ApiError(422, errors)
    title = data["title"].strip()
    article = Article(
        slug=f"{slugify(title)}-{secrets.token_hex(4)}",
        title=title,
        description=data["description"],
        body=data["body"],
        author_id=user.id,
    )
    article.author = user
    article.tags = resolve_tags(db, data.get("tagList", []))
    db.add(article)
    db.commit()
    return article_json(db, article, user)


@app.get("/api/articles/{slug}")
def get_article(slug: str, db: Session = Depends(get_db), viewer=Depends(optional_user)):
    return article_json(db, find_article(db, slug), viewer)


@app.put("/api/articles/{slug}")
async def update_article(slug: str, request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
    article = find_article(db, slug)
    if article.author_id != user.id:
        raise err(403, "article", "forbidden")
    data = await read_object(request, "article")
    errors = {}
    for field in ("title", "description", "body"):
        if field in data and blank(data[field]):
            errors[field] = ["can't be blank"]
    if "tagList" in data:
        validate_tag_list(data["tagList"], errors)
    if errors:
        raise ApiError(422, errors)
    if "title" in data:
        article.title = data["title"].strip()
    if "description" in data:
        article.description = data["description"]
    if "body" in data:
        article.body = data["body"]
    if "tagList" in data:
        article.tags = resolve_tags(db, data["tagList"])
    article.updated_at = utcnow()
    db.commit()
    return article_json(db, article, user)


@app.delete("/api/articles/{slug}", status_code=204)
def delete_article(slug: str, db: Session = Depends(get_db), user: User = Depends(current_user)):
    article = find_article(db, slug)
    if article.author_id != user.id:
        raise err(403, "article", "forbidden")
    db.delete(article)
    db.commit()
    return Response(status_code=204)


# ---------- favorites ----------

@app.post("/api/articles/{slug}/favorite")
def favorite(slug: str, db: Session = Depends(get_db), user: User = Depends(current_user)):
    article = find_article(db, slug)
    exists = db.execute(
        select(favorites.c.user_id).where(favorites.c.user_id == user.id, favorites.c.article_id == article.id)
    ).first()
    if not exists:
        db.execute(insert(favorites).values(user_id=user.id, article_id=article.id))
        db.commit()
    return article_json(db, article, user)


@app.delete("/api/articles/{slug}/favorite")
def unfavorite(slug: str, db: Session = Depends(get_db), user: User = Depends(current_user)):
    article = find_article(db, slug)
    db.execute(delete(favorites).where(favorites.c.user_id == user.id, favorites.c.article_id == article.id))
    db.commit()
    return article_json(db, article, user)


# ---------- comments ----------

def comment_json(db: Session, c: Comment, viewer) -> dict:
    return {
        "id": c.id,
        "createdAt": iso(c.created_at),
        "updatedAt": iso(c.updated_at),
        "body": c.body,
        "author": profile_dict(c.author, is_following(db, viewer, c.author)),
    }


@app.post("/api/articles/{slug}/comments", status_code=201)
async def add_comment(slug: str, request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
    article = find_article(db, slug)
    data = await read_object(request, "comment")
    if blank(data.get("body")):
        raise err(422, "body", "can't be blank")
    comment = Comment(body=data["body"], article_id=article.id, author_id=user.id)
    comment.author = user
    db.add(comment)
    db.commit()
    return {"comment": comment_json(db, comment, user)}


@app.get("/api/articles/{slug}/comments")
def get_comments(slug: str, db: Session = Depends(get_db), viewer=Depends(optional_user)):
    article = find_article(db, slug)
    rows = (
        db.execute(
            select(Comment)
            .where(Comment.article_id == article.id)
            .order_by(Comment.created_at.desc(), Comment.id.desc())
            .options(selectinload(Comment.author))
        )
        .scalars()
        .all()
    )
    followed = set()
    if viewer is not None:
        followed = set(db.execute(select(follows.c.followed_id).where(follows.c.follower_id == viewer.id)).scalars())
    return {
        "comments": [
            {
                "id": c.id,
                "createdAt": iso(c.created_at),
                "updatedAt": iso(c.updated_at),
                "body": c.body,
                "author": profile_dict(c.author, c.author_id in followed),
            }
            for c in rows
        ]
    }


@app.put("/api/articles/{slug}/comments/{comment_id}")
async def update_comment(slug: str, comment_id: int, request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
    article = find_article(db, slug)
    comment = db.get(Comment, comment_id)
    if comment is None or comment.article_id != article.id:
        raise err(404, "comment", "not found")
    if comment.author_id != user.id:
        raise err(403, "comment", "forbidden")
    data = await read_object(request, "comment")
    if blank(data.get("body")):
        raise err(422, "body", "can't be blank")
    comment.body = data["body"]
    comment.updated_at = utcnow()
    db.commit()
    return {"comment": comment_json(db, comment, user)}


@app.delete("/api/articles/{slug}/comments/{comment_id}", status_code=204)
def delete_comment(slug: str, comment_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    article = find_article(db, slug)
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
    names = db.execute(
        select(Tag.name).where(Tag.id.in_(select(article_tags.c.tag_id))).order_by(Tag.name)
    ).scalars().all()
    return {"tags": names}
