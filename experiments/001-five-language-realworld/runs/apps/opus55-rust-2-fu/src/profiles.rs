use axum::{
    extract::{Path, State},
    Json,
};
use serde::Serialize;
use serde_json::{json, Value};

use crate::{
    auth::{AuthUser, MaybeAuthUser},
    error::{ApiError, ApiResult},
    AppState,
};

#[derive(Serialize, sqlx::FromRow)]
pub struct Profile {
    #[serde(skip)]
    pub id: i64,
    pub username: String,
    pub bio: Option<String>,
    pub image: Option<String>,
    pub following: bool,
}

async fn load(state: &AppState, username: &str, viewer_id: i64) -> ApiResult<Profile> {
    sqlx::query_as(
        "SELECT u.id, u.username, u.bio, u.image,
                EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ?1 AND f.followee_id = u.id) AS following
         FROM users u WHERE u.username = ?2",
    )
    .bind(viewer_id)
    .bind(username)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| ApiError::not_found("profile"))
}

fn profile_response(profile: Profile) -> Json<Value> {
    Json(json!({ "profile": profile }))
}

pub async fn get_profile(
    State(state): State<AppState>,
    viewer: MaybeAuthUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    Ok(profile_response(load(&state, &username, viewer.viewer_id()).await?))
}

pub async fn follow(
    State(state): State<AppState>,
    user: AuthUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    let mut profile = load(&state, &username, user.id).await?;
    if profile.id == user.id {
        return Err(ApiError::validation("profile", "cannot follow yourself"));
    }
    sqlx::query("INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)")
        .bind(user.id)
        .bind(profile.id)
        .execute(&state.pool)
        .await?;
    profile.following = true;
    Ok(profile_response(profile))
}

pub async fn unfollow(
    State(state): State<AppState>,
    user: AuthUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    let mut profile = load(&state, &username, user.id).await?;
    sqlx::query("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?")
        .bind(user.id)
        .bind(profile.id)
        .execute(&state.pool)
        .await?;
    profile.following = false;
    Ok(profile_response(profile))
}
