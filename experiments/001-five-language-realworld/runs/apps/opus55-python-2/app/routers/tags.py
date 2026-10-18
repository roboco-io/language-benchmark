from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import ArticleTag, Tag

router = APIRouter(prefix="/api")


@router.get("/tags")
def list_tags(db: Session = Depends(get_db)):
    stmt = select(Tag.name).where(select(ArticleTag.id).where(ArticleTag.tag_id == Tag.id).exists()).order_by(Tag.name)
    return {"tags": list(db.scalars(stmt))}
