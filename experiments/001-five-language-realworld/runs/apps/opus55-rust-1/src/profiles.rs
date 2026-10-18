use axum::{
    extract::{Path, State},
    Json,
};
use serde_json::{json, Value};

use crate::{
    auth::{AuthUser, MaybeUser},
    error::{AppError, AppResult},
    AppState,
};

#[derive(sqlx::FromRow)]
pub struct ProfileRow {
    pub id: i64,
    pub username: String,
    pub bio: Option<String>,
    pub image: Option<String>,
    pub following: bool,
}

impl ProfileRow {
    pub fn to_json(&self) -> Value {
        json!({
            "username": self.username,
            "bio": self.bio,
            "image": self.image,
            "following": self.following,
        })
    }
}

async fn load_profile(state: &AppState, username: &str, viewer: Option<i64>) -> AppResult<ProfileRow> {
    sqlx::query_as(
        "SELECT u.id, u.username, u.bio, u.image, \
         EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS following \
         FROM users u WHERE u.username = ?",
    )
    .bind(viewer)
    .bind(username)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::not_found("profile"))
}

fn profile_response(profile: &ProfileRow) -> Json<Value> {
    Json(json!({ "profile": profile.to_json() }))
}

pub async fn get_profile(
    State(state): State<AppState>,
    viewer: MaybeUser,
    Path(username): Path<String>,
) -> AppResult<Json<Value>> {
    let profile = load_profile(&state, &username, viewer.id()).await?;
    Ok(profile_response(&profile))
}

pub async fn follow(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(username): Path<String>,
) -> AppResult<Json<Value>> {
    let mut profile = load_profile(&state, &username, Some(auth.id)).await?;
    if profile.id != auth.id {
        sqlx::query("INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)")
            .bind(auth.id)
            .bind(profile.id)
            .execute(&state.pool)
            .await?;
        profile.following = true;
    }
    Ok(profile_response(&profile))
}

pub async fn unfollow(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(username): Path<String>,
) -> AppResult<Json<Value>> {
    let mut profile = load_profile(&state, &username, Some(auth.id)).await?;
    sqlx::query("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?")
        .bind(auth.id)
        .bind(profile.id)
        .execute(&state.pool)
        .await?;
    profile.following = false;
    Ok(profile_response(&profile))
}
