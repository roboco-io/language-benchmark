"""Production launcher: create the schema once, then run uvicorn with one worker per CPU core."""

import os

import uvicorn

from .db import init_db


def cpu_count() -> int:
    counter = getattr(os, "process_cpu_count", None) or os.cpu_count
    return max(1, counter() or 1)


def main() -> None:
    init_db()
    port = int(os.environ.get("PORT", "8000"))
    host = os.environ.get("HOST", "0.0.0.0")
    workers = int(os.environ.get("WEB_CONCURRENCY", cpu_count()))
    print(f"Starting RealWorld API on {host}:{port} with {workers} workers", flush=True)
    uvicorn.run("app.main:app", host=host, port=port, workers=workers, proxy_headers=True, access_log=False)


if __name__ == "__main__":
    main()
