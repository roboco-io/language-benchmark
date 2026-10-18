use axum::{extract::State, http::StatusCode, Json};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{
    auth::{self, AuthUser},
    db,
    error::{double_option, ApiError, ApiJson, ApiResult},
    AppState,
};

const MIN_PASSWORD_LEN: usize = 8;

#[derive(sqlx::FromRow)]
struct UserRow {
    id: i64,
    username: String,
    email: String,
    password_hash: String,
    bio: Option<String>,
    image: Option<String>,
}

#[derive(Serialize)]
struct UserBody {
    username: String,
    email: String,
    bio: Option<String>,
    image: Option<String>,
    token: String,
}

fn user_response(state: &AppState, row: UserRow) -> ApiResult<Json<Value>> {
    let token = auth::create_token(state, row.id)?;
    let user = UserBody { username: row.username, email: row.email, bio: row.bio, image: row.image, token };
    Ok(Json(json!({ "user": user })))
}

async fn find_by_id(state: &AppState, id: i64) -> ApiResult<Option<UserRow>> {
    Ok(sqlx::query_as("SELECT id, username, email, password_hash, bio, image FROM users WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.pool)
        .await?)
}

/// Returns the trimmed value, or a "can't be blank" error when it is missing or empty.
fn required(value: Option<String>, field: &str) -> ApiResult<String> {
    match value.map(|v| v.trim().to_string()) {
        Some(v) if !v.is_empty() => Ok(v),
        _ => Err(ApiError::blank(field)),
    }
}

fn check_password(password: &str) -> ApiResult<()> {
    if password.is_empty() {
        return Err(ApiError::blank("password"));
    }
    if password.chars().count() < MIN_PASSWORD_LEN {
        return Err(ApiError::validation("password", "is too short (minimum is 8 characters)"));
    }
    Ok(())
}

/// Treats empty strings as null for optional profile fields.
fn normalize_optional(value: Option<String>) -> Option<String> {
    value.filter(|v| !v.trim().is_empty())
}

async fn ensure_unique(state: &AppState, username: Option<&str>, email: Option<&str>, except_id: i64) -> ApiResult<()> {
    if let Some(username) = username {
        let taken: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE username = ? AND id != ?")
            .bind(username)
            .bind(except_id)
            .fetch_optional(&state.pool)
            .await?;
        if taken.is_some() {
            return Err(ApiError::taken("username"));
        }
    }
    if let Some(email) = email {
        let taken: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE email = ? AND id != ?")
            .bind(email)
            .bind(except_id)
            .fetch_optional(&state.pool)
            .await?;
        if taken.is_some() {
            return Err(ApiError::taken("email"));
        }
    }
    Ok(())
}

/// Maps a unique-constraint failure that slipped past `ensure_unique` (a race) to 409.
fn map_unique(err: sqlx::Error) -> ApiError {
    if db::is_unique_violation(&err) {
        let field = if err.to_string().contains("users.email") { "email" } else { "username" };
        ApiError::taken(field)
    } else {
        err.into()
    }
}

#[derive(Deserialize)]
pub struct RegisterRequest {
    user: RegisterUser,
}

#[derive(Deserialize)]
struct RegisterUser {
    username: Option<String>,
    email: Option<String>,
    password: Option<String>,
}

pub async fn register(
    State(state): State<AppState>,
    ApiJson(req): ApiJson<RegisterRequest>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let username = required(req.user.username, "username")?;
    let email = required(req.user.email, "email")?;
    let password = req.user.password.unwrap_or_default();
    check_password(&password)?;
    ensure_unique(&state, Some(&username), Some(&email), 0).await?;

    let hash = auth::hash_password(password).await?;
    let id: i64 = sqlx::query_scalar("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?) RETURNING id")
        .bind(&username)
        .bind(&email)
        .bind(&hash)
        .fetch_one(&state.pool)
        .await
        .map_err(map_unique)?;

    let row = UserRow { id, username, email, password_hash: hash, bio: None, image: None };
    Ok((StatusCode::CREATED, user_response(&state, row)?))
}

#[derive(Deserialize)]
pub struct LoginRequest {
    user: LoginUser,
}

#[derive(Deserialize)]
struct LoginUser {
    email: Option<String>,
    password: Option<String>,
}

pub async fn login(State(state): State<AppState>, ApiJson(req): ApiJson<LoginRequest>) -> ApiResult<Json<Value>> {
    let email = required(req.user.email, "email")?;
    let password = req.user.password.filter(|p| !p.is_empty()).ok_or_else(|| ApiError::blank("password"))?;

    let row: Option<UserRow> =
        sqlx::query_as("SELECT id, username, email, password_hash, bio, image FROM users WHERE email = ?")
            .bind(&email)
            .fetch_optional(&state.pool)
            .await?;
    let invalid = || ApiError::unauthorized("credentials", "invalid");
    let row = row.ok_or_else(invalid)?;
    if !auth::verify_password(password, row.password_hash.clone()).await? {
        return Err(invalid());
    }
    user_response(&state, row)
}

pub async fn current_user(State(state): State<AppState>, user: AuthUser) -> ApiResult<Json<Value>> {
    let row = find_by_id(&state, user.id).await?.ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;
    user_response(&state, row)
}

#[derive(Deserialize)]
pub struct UpdateRequest {
    user: UpdateUser,
}

#[derive(Deserialize)]
struct UpdateUser {
    #[serde(default, deserialize_with = "double_option")]
    username: Option<Option<String>>,
    #[serde(default, deserialize_with = "double_option")]
    email: Option<Option<String>>,
    #[serde(default, deserialize_with = "double_option")]
    password: Option<Option<String>>,
    #[serde(default, deserialize_with = "double_option")]
    bio: Option<Option<String>>,
    #[serde(default, deserialize_with = "double_option")]
    image: Option<Option<String>>,
}

pub async fn update_user(
    State(state): State<AppState>,
    user: AuthUser,
    ApiJson(req): ApiJson<UpdateRequest>,
) -> ApiResult<Json<Value>> {
    let mut row = find_by_id(&state, user.id).await?.ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;
    let changes = req.user;

    let username = changes.username.map(|v| required(v, "username")).transpose()?;
    let email = changes.email.map(|v| required(v, "email")).transpose()?;
    let password = match changes.password {
        Some(value) => {
            let value = value.unwrap_or_default();
            check_password(&value)?;
            Some(value)
        }
        None => None,
    };
    ensure_unique(&state, username.as_deref(), email.as_deref(), row.id).await?;

    if let Some(username) = username {
        row.username = username;
    }
    if let Some(email) = email {
        row.email = email;
    }
    if let Some(bio) = changes.bio {
        row.bio = normalize_optional(bio);
    }
    if let Some(image) = changes.image {
        row.image = normalize_optional(image);
    }
    if let Some(password) = password {
        row.password_hash = auth::hash_password(password).await?;
    }

    sqlx::query("UPDATE users SET username = ?, email = ?, password_hash = ?, bio = ?, image = ? WHERE id = ?")
        .bind(&row.username)
        .bind(&row.email)
        .bind(&row.password_hash)
        .bind(&row.bio)
        .bind(&row.image)
        .bind(row.id)
        .execute(&state.pool)
        .await
        .map_err(map_unique)?;

    user_response(&state, row)
}
