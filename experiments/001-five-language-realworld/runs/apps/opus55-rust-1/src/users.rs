use axum::{extract::State, http::StatusCode, Json};
use serde_json::{json, Value};

use crate::{
    auth::{create_token, hash_password, verify_password, AuthUser},
    body::{optional_nonblank_str, optional_nullable_str, required_str, JsonBody},
    error::{AppError, AppResult},
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

fn user_json(user: &UserRow, token: &str) -> Json<Value> {
    Json(json!({
        "user": {
            "username": user.username,
            "email": user.email,
            "token": token,
            "bio": user.bio,
            "image": user.image,
        }
    }))
}

async fn find_user(state: &AppState, column: &str, value: impl ToString) -> AppResult<Option<UserRow>> {
    let sql = format!(
        "SELECT id, username, email, password_hash, bio, image FROM users WHERE {column} = ?"
    );
    Ok(sqlx::query_as(&sql)
        .bind(value.to_string())
        .fetch_optional(&state.pool)
        .await?)
}

async fn ensure_unique(state: &AppState, column: &str, value: &str, except_id: i64) -> AppResult<()> {
    let sql = format!("SELECT EXISTS(SELECT 1 FROM users WHERE {column} = ? AND id != ?)");
    let taken: bool = sqlx::query_scalar(&sql)
        .bind(value)
        .bind(except_id)
        .fetch_one(&state.pool)
        .await?;
    if taken {
        Err(AppError::taken(column))
    } else {
        Ok(())
    }
}

fn check_password_policy(password: &str) -> AppResult<()> {
    if password.chars().count() < MIN_PASSWORD_LEN {
        return Err(AppError::invalid(
            "password",
            "is too short (minimum is 8 characters)",
        ));
    }
    Ok(())
}

pub async fn register(
    State(state): State<AppState>,
    body: JsonBody,
) -> AppResult<(StatusCode, Json<Value>)> {
    let input = body.object("user");
    let username = required_str(&input, "username")?;
    let email = required_str(&input, "email")?;
    let password = required_str(&input, "password")?;
    check_password_policy(&password)?;

    ensure_unique(&state, "username", &username, 0).await?;
    ensure_unique(&state, "email", &email, 0).await?;

    let password_hash = hash_password(password).await?;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
    )
    .bind(&username)
    .bind(&email)
    .bind(&password_hash)
    .fetch_one(&state.pool)
    .await?;

    let user = UserRow {
        id,
        username,
        email,
        password_hash,
        bio: None,
        image: None,
    };
    let token = create_token(&state, user.id)?;
    Ok((StatusCode::CREATED, user_json(&user, &token)))
}

pub async fn login(State(state): State<AppState>, body: JsonBody) -> AppResult<Json<Value>> {
    let input = body.object("user");
    let email = required_str(&input, "email")?;
    let password = required_str(&input, "password")?;

    let invalid = || AppError::unauthorized("credentials", "invalid");
    let user = find_user(&state, "email", &email).await?.ok_or_else(invalid)?;
    if !verify_password(password, user.password_hash.clone()).await? {
        return Err(invalid());
    }
    let token = create_token(&state, user.id)?;
    Ok(user_json(&user, &token))
}

pub async fn current_user(State(state): State<AppState>, auth: AuthUser) -> AppResult<Json<Value>> {
    let user = find_user(&state, "id", auth.id)
        .await?
        .ok_or_else(|| AppError::unauthorized("token", "is invalid"))?;
    Ok(user_json(&user, &auth.token))
}

pub async fn update_user(
    State(state): State<AppState>,
    auth: AuthUser,
    body: JsonBody,
) -> AppResult<Json<Value>> {
    let input = body.object("user");
    let username = optional_nonblank_str(&input, "username")?;
    let email = optional_nonblank_str(&input, "email")?;
    let password = optional_nonblank_str(&input, "password")?;
    let bio = optional_nullable_str(&input, "bio")?;
    let image = optional_nullable_str(&input, "image")?;

    let mut user = find_user(&state, "id", auth.id)
        .await?
        .ok_or_else(|| AppError::unauthorized("token", "is invalid"))?;

    if let Some(username) = username {
        ensure_unique(&state, "username", &username, user.id).await?;
        user.username = username;
    }
    if let Some(email) = email {
        ensure_unique(&state, "email", &email, user.id).await?;
        user.email = email;
    }
    if let Some(password) = password {
        check_password_policy(&password)?;
        user.password_hash = hash_password(password).await?;
    }
    if let Some(bio) = bio {
        user.bio = bio;
    }
    if let Some(image) = image {
        user.image = image;
    }

    sqlx::query(
        "UPDATE users SET username = ?, email = ?, password_hash = ?, bio = ?, image = ? WHERE id = ?",
    )
    .bind(&user.username)
    .bind(&user.email)
    .bind(&user.password_hash)
    .bind(&user.bio)
    .bind(&user.image)
    .bind(user.id)
    .execute(&state.pool)
    .await?;

    Ok(user_json(&user, &auth.token))
}
