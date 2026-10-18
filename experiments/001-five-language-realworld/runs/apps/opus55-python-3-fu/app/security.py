"""Password hashing and JWT helpers."""

import base64
import hashlib
import hmac
import os
import secrets
from datetime import datetime, timedelta, timezone

import jwt

from .db import DATABASE_PATH

_ITERATIONS = 100_000
_ALGORITHM = "HS256"
_TOKEN_TTL = timedelta(days=30)


def _load_secret() -> str:
    """Return the JWT secret.

    Every worker process must share the same secret. When JWT_SECRET is not
    set, a random secret is generated once and stored next to the database.
    """
    env_secret = os.environ.get("JWT_SECRET")
    if env_secret:
        return env_secret
    path = os.path.join(os.path.dirname(os.path.abspath(DATABASE_PATH)), ".jwt_secret")
    try:
        with open(path, "x") as f:
            f.write(secrets.token_hex(32))
    except FileExistsError:
        pass
    with open(path) as f:
        return f.read().strip()


SECRET = _load_secret()


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, _ITERATIONS)
    return "pbkdf2$%d$%s$%s" % (
        _ITERATIONS,
        base64.b64encode(salt).decode(),
        base64.b64encode(digest).decode(),
    )


def verify_password(password: str, stored: str) -> bool:
    try:
        _, iterations, salt_b64, digest_b64 = stored.split("$")
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(digest_b64)
    except ValueError:
        return False
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, int(iterations))
    return hmac.compare_digest(digest, expected)


def create_token(user_id: int) -> str:
    now = datetime.now(timezone.utc)
    payload = {"sub": str(user_id), "iat": now, "exp": now + _TOKEN_TTL}
    return jwt.encode(payload, SECRET, algorithm=_ALGORITHM)


def decode_token(token: str) -> int | None:
    try:
        payload = jwt.decode(token, SECRET, algorithms=[_ALGORITHM])
        return int(payload["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        return None
