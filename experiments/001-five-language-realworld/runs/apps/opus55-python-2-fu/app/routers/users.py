from typing import Any

from fastapi import APIRouter, Body, Depends
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..db import get_db
from ..errors import APIError, unprocessable
from ..models import User
from ..security import create_token, current_user, hash_password, verify_password
from ..serializers import user_json
from ..validation import nullable_string, optional_required_string, present, required_string, unwrap

router = APIRouter(prefix="/api")

MIN_PASSWORD_LENGTH = 8
TAKEN = "has already been taken"


def _ensure_unique(db: Session, field: str, value: str, exclude_id: int | None = None) -> None:
    stmt = select(User.id).where(getattr(User, field) == value)
    if exclude_id is not None:
        stmt = stmt.where(User.id != exclude_id)
    if db.execute(stmt).first() is not None:
        raise APIError(409, field, TAKEN)


def _flush_unique(db: Session) -> None:
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()
        field = "email" if "email" in str(exc.orig) else "username"
        raise APIError(409, field, TAKEN)


@router.post("/users", status_code=201)
def register(payload: Any = Body(...), db: Session = Depends(get_db)):
    data = unwrap(payload, "user")
    username = required_string(data, "username")
    email = required_string(data, "email")
    password = required_string(data, "password")
    _ensure_unique(db, "username", username)
    _ensure_unique(db, "email", email)
    user = User(username=username, email=email, password_hash=hash_password(password))
    db.add(user)
    _flush_unique(db)
    return user_json(user, create_token(user))


@router.post("/users/login")
def login(payload: Any = Body(...), db: Session = Depends(get_db)):
    data = unwrap(payload, "user")
    email = required_string(data, "email")
    password = required_string(data, "password")
    user = db.scalars(select(User).where(User.email == email)).first()
    if user is None or not verify_password(password, user.password_hash):
        raise APIError(401, "credentials", "invalid")
    return user_json(user, create_token(user))


@router.get("/user")
def get_current_user(user: User = Depends(current_user)):
    return user_json(user, create_token(user))


@router.put("/user")
def update_user(payload: Any = Body(...), user: User = Depends(current_user), db: Session = Depends(get_db)):
    data = unwrap(payload, "user")
    username = optional_required_string(data, "username")
    email = optional_required_string(data, "email")
    password = optional_required_string(data, "password")
    bio = nullable_string(data, "bio")
    image = nullable_string(data, "image")

    if present(password) and len(password) < MIN_PASSWORD_LENGTH:
        raise unprocessable("password", f"is too short (minimum is {MIN_PASSWORD_LENGTH} characters)")
    if present(username) and username != user.username:
        _ensure_unique(db, "username", username, exclude_id=user.id)
        user.username = username
    if present(email) and email != user.email:
        _ensure_unique(db, "email", email, exclude_id=user.id)
        user.email = email
    if present(password):
        user.password_hash = hash_password(password)
    if present(bio):
        user.bio = bio
    if present(image):
        user.image = image
    _flush_unique(db)
    return user_json(user, create_token(user))
