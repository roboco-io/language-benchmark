from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import errors
from .db import init_db
from .routers import articles, profiles, tags, users


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # The launcher (app.serve) creates the schema before forking workers; this
    # is a safety net for running `uvicorn app.main:app` directly.
    init_db()
    yield


app = FastAPI(title="RealWorld API", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
errors.register(app)
for module in (users, profiles, articles, tags):
    app.include_router(module.router)
