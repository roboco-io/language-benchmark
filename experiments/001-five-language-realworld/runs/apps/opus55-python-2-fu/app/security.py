import base64
import hashlib
import hmac
import os
import time

import jwt
from fastapi import Depends, Request
from sqlalchemy.orm import Session

from .config import JWT_ALGORITHM, JWT_SECRET, JWT_TTL_SECONDS
from .db import get_db
from .errors import APIError
from .models import User

_SCRYPT_N, _SCRYPT_R, _SCRYPT_P = 2**14, 8, 1


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P)
    return "scrypt$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(digest).decode()


def verify_password(password: str, stored: str) -> bool:
    try:
        _scheme, salt_b64, digest_b64 = stored.split("$")
        salt, expected = base64.b64decode(salt_b64), base64.b64decode(digest_b64)
    except ValueError:
        return False
    digest = hashlib.scrypt(password.encode(), salt=salt, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P)
    return hmac.compare_digest(digest, expected)


def create_token(user: User) -> str:
    now = int(time.time())
    payload = {"sub": str(user.id), "username": user.username, "iat": now, "exp": now + JWT_TTL_SECONDS}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def _extract_token(request: Request) -> str | None:
    header = request.headers.get("Authorization")
    if not header:
        return None
    scheme, _, token = header.partition(" ")
    if scheme.lower() not in ("token", "bearer") or not token.strip():
        raise APIError(401, "token", "is invalid")
    return token.strip()


def _user_from_token(token: str, db: Session) -> User:
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user_id = int(payload["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        raise APIError(401, "token", "is invalid")
    user = db.get(User, user_id)
    if user is None:
        raise APIError(401, "token", "is invalid")
    return user


def optional_user(request: Request, db: Session = Depends(get_db)) -> User | None:
    token = _extract_token(request)
    return _user_from_token(token, db) if token else None


def current_user(request: Request, db: Session = Depends(get_db)) -> User:
    token = _extract_token(request)
    if token is None:
        raise APIError(401, "token", "is missing")
    return _user_from_token(token, db)
