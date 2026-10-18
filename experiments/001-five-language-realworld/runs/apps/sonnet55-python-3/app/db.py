import os

from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DATABASE_PATH = os.environ.get("DATABASE_PATH", "realworld.db")

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
    """Create the schema. Safe to call from several workers at once."""
    import time

    from sqlalchemy.exc import OperationalError

    from . import models  # noqa: F401

    for attempt in range(10):
        try:
            Base.metadata.create_all(engine)
            return
        except OperationalError:
            if attempt == 9:
                raise
            time.sleep(0.2)
