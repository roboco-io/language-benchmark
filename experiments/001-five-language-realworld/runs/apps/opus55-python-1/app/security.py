"""Password hashing and JWT helpers."""

import hashlib
import hmac
import os
import secrets
from datetime import datetime, timedelta, timezone

import jwt

# All workers must share the same secret, so it is never generated per process.
JWT_SECRET = os.environ.get("JWT_SECRET", "realworld-dev-secret-change-me")
JWT_ALGORITHM = "HS256"
JWT_TTL = timedelta(days=int(os.environ.get("JWT_TTL_DAYS", "30")))
PBKDF2_ITERATIONS = int(os.environ.get("PBKDF2_ITERATIONS", "60000"))


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), salt.encode(), PBKDF2_ITERATIONS
    ).hex()
    return f"pbkdf2_sha256${PBKDF2_ITERATIONS}${salt}${digest}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _algo, iterations, salt, digest = stored.split("$")
    except ValueError:
        return False
    candidate = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), salt.encode(), int(iterations)
    ).hex()
    return hmac.compare_digest(candidate, digest)


def create_token(user_id: int) -> str:
    now = datetime.now(timezone.utc)
    payload = {"sub": str(user_id), "iat": now, "exp": now + JWT_TTL}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def decode_token(token: str) -> int | None:
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        return int(payload["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        return None
