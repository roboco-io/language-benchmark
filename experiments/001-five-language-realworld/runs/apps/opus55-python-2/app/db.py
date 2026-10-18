import os

from fastapi import Request
from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from .config import DATABASE_PATH


class Base(DeclarativeBase):
    pass


def _make_engine(begin_statement: str):
    directory = os.path.dirname(os.path.abspath(DATABASE_PATH))
    os.makedirs(directory, exist_ok=True)
    engine = create_engine(f"sqlite:///{DATABASE_PATH}", pool_pre_ping=False)

    @event.listens_for(engine, "connect")
    def _on_connect(dbapi_conn, _record):
        # Disable pysqlite's implicit transaction handling so we control BEGIN.
        dbapi_conn.isolation_level = None
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA busy_timeout=5000")
        cur.execute("PRAGMA synchronous=NORMAL")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

    @event.listens_for(engine, "begin")
    def _on_begin(conn):
        conn.exec_driver_sql(begin_statement)

    return engine


# Read requests use deferred transactions; write requests take the write lock
# up front (BEGIN IMMEDIATE) so concurrent writers wait on busy_timeout instead
# of failing on a read-to-write lock upgrade.
read_engine = _make_engine("BEGIN")
write_engine = _make_engine("BEGIN IMMEDIATE")
ReadSession = sessionmaker(bind=read_engine, expire_on_commit=False)
WriteSession = sessionmaker(bind=write_engine, expire_on_commit=False)


def get_db(request: Request):
    factory = ReadSession if request.method in ("GET", "HEAD", "OPTIONS") else WriteSession
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def init_db() -> None:
    from . import models  # noqa: F401  (register tables)

    Base.metadata.create_all(write_engine)
