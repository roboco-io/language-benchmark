"""Server launcher: initializes the schema once, then starts uvicorn with one worker per CPU core."""
import os

import uvicorn

from app.db import init_db


def cpu_count() -> int:
    try:
        return len(os.sched_getaffinity(0))
    except AttributeError:
        return os.cpu_count() or 1


if __name__ == "__main__":
    init_db()
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8000")),
        workers=int(os.environ.get("WORKERS", cpu_count())),
        log_level="warning",
    )
