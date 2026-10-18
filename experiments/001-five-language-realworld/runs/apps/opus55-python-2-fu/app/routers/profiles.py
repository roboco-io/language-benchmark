from fastapi import APIRouter, Depends
from sqlalchemy import delete, select
from sqlalchemy.dialects.sqlite import insert
from sqlalchemy.orm import Session

from ..db import get_db
from ..errors import not_found
from ..models import User, follows
from ..security import current_user, optional_user
from ..serializers import is_following, profile_dict

router = APIRouter(prefix="/api/profiles")


def _get_user(db: Session, username: str) -> User:
    user = db.scalars(select(User).where(User.username == username)).first()
    if user is None:
        raise not_found("profile")
    return user


@router.get("/{username}")
def get_profile(username: str, viewer: User | None = Depends(optional_user), db: Session = Depends(get_db)):
    target = _get_user(db, username)
    return {"profile": profile_dict(target, is_following(db, viewer, target))}


@router.post("/{username}/follow")
def follow(username: str, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    target = _get_user(db, username)
    db.execute(
        insert(follows).values(follower_id=viewer.id, followee_id=target.id).on_conflict_do_nothing()
    )
    return {"profile": profile_dict(target, True)}


@router.delete("/{username}/follow")
def unfollow(username: str, viewer: User = Depends(current_user), db: Session = Depends(get_db)):
    target = _get_user(db, username)
    db.execute(delete(follows).where(follows.c.follower_id == viewer.id, follows.c.followee_id == target.id))
    return {"profile": profile_dict(target, False)}
