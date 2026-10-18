import os
import secrets

DATABASE_PATH = os.environ.get("DATABASE_PATH", os.path.join("data", "realworld.db"))
JWT_ALGORITHM = "HS256"
JWT_TTL_SECONDS = int(os.environ.get("JWT_TTL_SECONDS", str(60 * 60 * 24 * 30)))


def _load_jwt_secret() -> str:
    """Use $JWT_SECRET, or a random secret persisted next to the database.

    The file is created atomically so all workers (and restarts) share one secret.
    """
    if os.environ.get("JWT_SECRET"):
        return os.environ["JWT_SECRET"]
    directory = os.path.dirname(os.path.abspath(DATABASE_PATH))
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, "jwt_secret")
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "w") as f:
            f.write(secrets.token_urlsafe(48))
    for _ in range(50):
        with open(path) as f:
            value = f.read().strip()
        if value:
            return value
        import time

        time.sleep(0.01)
    raise RuntimeError(f"JWT secret file {path} is empty")


JWT_SECRET = _load_jwt_secret()
