"""Database engine and session setup (SQLite, WAL mode, busy_timeout 5000 ms)."""
import os

from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DATABASE_PATH = os.environ.get("DATABASE_PATH", "data/realworld.db")
_dir = os.path.dirname(os.path.abspath(DATABASE_PATH))
os.makedirs(_dir, exist_ok=True)

engine = create_engine(
    f"sqlite:///{DATABASE_PATH}",
    connect_args={"check_same_thread": False, "timeout": 5},
    pool_size=10,
    max_overflow=20,
)


@event.listens_for(engine, "connect")
def _set_pragmas(dbapi_conn, _record):
    cur = dbapi_conn.cursor()
    cur.execute("PRAGMA journal_mode=WAL")
    cur.execute("PRAGMA busy_timeout=5000")
    cur.execute("PRAGMA synchronous=NORMAL")
    cur.execute("PRAGMA foreign_keys=ON")
    cur.close()


SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def init_db():
    """Create the schema if it does not exist. Safe to call concurrently."""
    from . import models  # noqa: F401

    for attempt in range(5):
        try:
            Base.metadata.create_all(engine, checkfirst=True)
            return
        except Exception:
            if attempt == 4:
                raise
            import time

            time.sleep(0.2)
