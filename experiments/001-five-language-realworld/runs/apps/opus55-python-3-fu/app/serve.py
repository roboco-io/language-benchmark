"""Production entry point: create the schema, then run uvicorn workers.

The schema is created once in the parent process so that worker processes
do not race each other on an empty database.
"""

import os

import uvicorn

from .db import init_db


def cpu_count() -> int:
    try:
        return len(os.sched_getaffinity(0))
    except AttributeError:
        return os.cpu_count() or 1


def main() -> None:
    init_db()
    port = int(os.environ.get("PORT", "8000"))
    host = os.environ.get("HOST", "0.0.0.0")
    workers = cpu_count()
    print(f"Starting RealWorld API on {host}:{port} with {workers} workers", flush=True)
    uvicorn.run(
        "app.main:app",
        host=host,
        port=port,
        workers=workers,
        log_level=os.environ.get("LOG_LEVEL", "warning"),
        access_log=False,
    )


if __name__ == "__main__":
    main()
