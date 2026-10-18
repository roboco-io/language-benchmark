use axum::Json;
use axum::extract::{Path, State};
use serde_json::{Value, json};

use crate::AppState;
use crate::auth::{AuthUser, MaybeUser};
use crate::error::{ApiError, ApiResult};

#[derive(sqlx::FromRow)]
struct ProfileRow {
    id: i64,
    username: String,
    bio: Option<String>,
    image: Option<String>,
    following: bool,
}

async fn load(state: &AppState, username: &str, viewer: Option<i64>) -> ApiResult<ProfileRow> {
    sqlx::query_as::<_, ProfileRow>(
        "SELECT u.id, u.username, u.bio, u.image, \
         EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ?1 AND f.followee_id = u.id) AS following \
         FROM users u WHERE u.username = ?2",
    )
    .bind(viewer)
    .bind(username)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| ApiError::not_found("profile"))
}

fn profile_json(p: &ProfileRow) -> Json<Value> {
    Json(json!({
        "profile": {
            "username": p.username,
            "bio": p.bio,
            "image": p.image,
            "following": p.following,
        }
    }))
}

pub async fn get_profile(
    State(state): State<AppState>,
    MaybeUser(viewer): MaybeUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    Ok(profile_json(&load(&state, &username, viewer).await?))
}

pub async fn follow(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    let mut profile = load(&state, &username, Some(me)).await?;
    sqlx::query("INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)")
        .bind(me)
        .bind(profile.id)
        .execute(&state.pool)
        .await?;
    profile.following = true;
    Ok(profile_json(&profile))
}

pub async fn unfollow(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    let mut profile = load(&state, &username, Some(me)).await?;
    sqlx::query("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?")
        .bind(me)
        .bind(profile.id)
        .execute(&state.pool)
        .await?;
    profile.following = false;
    Ok(profile_json(&profile))
}
