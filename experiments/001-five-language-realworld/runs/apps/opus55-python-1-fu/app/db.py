"""Database engine, session factory and schema bootstrap."""

import fcntl
import os

from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DATABASE_PATH = os.environ.get("DATABASE_PATH", "realworld.db")

db_dir = os.path.dirname(os.path.abspath(DATABASE_PATH))
os.makedirs(db_dir, exist_ok=True)

engine = create_engine(
    f"sqlite:///{DATABASE_PATH}",
    connect_args={"check_same_thread": False, "timeout": 5},
    pool_pre_ping=False,
)


@event.listens_for(engine, "connect")
def _set_sqlite_pragmas(dbapi_conn, _record):
    cur = dbapi_conn.cursor()
    cur.execute("PRAGMA journal_mode=WAL")
    cur.execute("PRAGMA busy_timeout=5000")
    cur.execute("PRAGMA foreign_keys=ON")
    cur.execute("PRAGMA synchronous=NORMAL")
    cur.close()


SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def init_schema() -> None:
    """Create tables if missing. A file lock serializes this across workers."""
    from . import models  # noqa: F401  (register models on Base.metadata)

    with open(os.path.abspath(DATABASE_PATH) + ".lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            Base.metadata.create_all(engine)
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
