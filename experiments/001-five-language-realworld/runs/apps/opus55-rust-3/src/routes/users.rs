use argon2::Argon2;
use argon2::password_hash::rand_core::OsRng;
use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde::Deserialize;
use serde_json::{Value, json};

use crate::AppState;
use crate::auth::AuthUser;
use crate::db::is_unique_violation;
use crate::error::{ApiError, ApiResult, Validation};
use crate::extract::{JsonBody, present};

const MIN_PASSWORD_LEN: usize = 8;
const MAX_PASSWORD_LEN: usize = 256;

#[derive(Deserialize)]
pub struct Envelope<T> {
    user: T,
}

#[derive(Deserialize)]
pub struct RegisterUser {
    username: Option<String>,
    email: Option<String>,
    password: Option<String>,
}

#[derive(Deserialize)]
pub struct LoginUser {
    email: Option<String>,
    password: Option<String>,
}

/// Every field distinguishes "absent" (`None`) from "explicit null"
/// (`Some(None)`).
#[derive(Deserialize)]
pub struct UpdateUser {
    #[serde(default, deserialize_with = "present")]
    username: Option<Option<String>>,
    #[serde(default, deserialize_with = "present")]
    email: Option<Option<String>>,
    #[serde(default, deserialize_with = "present")]
    password: Option<Option<String>>,
    #[serde(default, deserialize_with = "present")]
    bio: Option<Option<String>>,
    #[serde(default, deserialize_with = "present")]
    image: Option<Option<String>>,
}

#[derive(sqlx::FromRow)]
struct UserRow {
    id: i64,
    username: String,
    email: String,
    password_hash: String,
    bio: Option<String>,
    image: Option<String>,
}

fn user_json(state: &AppState, u: &UserRow) -> Value {
    json!({
        "user": {
            "username": u.username,
            "email": u.email,
            "bio": u.bio,
            "image": u.image,
            "token": state.jwt.issue(u.id),
        }
    })
}

fn is_blank(s: &Option<String>) -> bool {
    s.as_deref().map(str::trim).unwrap_or("").is_empty()
}

/// Empty strings for nullable profile fields are stored as NULL.
fn normalize_nullable(s: Option<String>) -> Option<String> {
    s.filter(|v| !v.trim().is_empty())
}

async fn hash_password(password: String) -> ApiResult<String> {
    tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|h| h.to_string())
    })
    .await
    .map_err(|_| ApiError::internal())?
    .map_err(|_| ApiError::internal())
}

async fn verify_password(password: String, hash: String) -> bool {
    tokio::task::spawn_blocking(move || {
        PasswordHash::new(&hash)
            .map(|parsed| {
                Argon2::default()
                    .verify_password(password.as_bytes(), &parsed)
                    .is_ok()
            })
            .unwrap_or(false)
    })
    .await
    .unwrap_or(false)
}

async fn find_by_id(state: &AppState, id: i64) -> ApiResult<Option<UserRow>> {
    Ok(
        sqlx::query_as::<_, UserRow>("SELECT * FROM users WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.pool)
            .await?,
    )
}

async fn taken(state: &AppState, column: &str, value: &str, except_id: i64) -> ApiResult<bool> {
    let sql = format!("SELECT EXISTS(SELECT 1 FROM users WHERE {column} = ? AND id != ?)");
    Ok(sqlx::query_scalar::<_, bool>(&sql)
        .bind(value)
        .bind(except_id)
        .fetch_one(&state.pool)
        .await?)
}

/// Returns 409 with field-specific messages when username or email is in use.
async fn check_uniqueness(
    state: &AppState,
    username: Option<&str>,
    email: Option<&str>,
    except_id: i64,
) -> ApiResult<()> {
    let mut errors = Validation::default();
    if let Some(u) = username {
        if taken(state, "username", u, except_id).await? {
            errors.add("username", "has already been taken");
        }
    }
    if let Some(e) = email {
        if taken(state, "email", e, except_id).await? {
            errors.add("email", "has already been taken");
        }
    }
    errors.finish().map_err(|mut e| {
        e.status = StatusCode::CONFLICT;
        e
    })
}

fn conflict_on_unique(err: sqlx::Error) -> ApiError {
    if is_unique_violation(&err) {
        let msg = err.to_string();
        let field = if msg.contains("email") {
            "email"
        } else {
            "username"
        };
        ApiError::new(StatusCode::CONFLICT, field, "has already been taken")
    } else {
        err.into()
    }
}

pub async fn register(
    State(state): State<AppState>,
    JsonBody(body): JsonBody<Envelope<RegisterUser>>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let RegisterUser {
        username,
        email,
        password,
    } = body.user;

    let mut v = Validation::default();
    if is_blank(&username) {
        v.add("username", "can't be blank");
    }
    if is_blank(&email) {
        v.add("email", "can't be blank");
    }
    if password.as_deref().unwrap_or("").is_empty() {
        v.add("password", "can't be blank");
    }
    v.finish()?;

    let username = username.unwrap().trim().to_string();
    let email = email.unwrap().trim().to_string();
    let password = password.unwrap();

    check_uniqueness(&state, Some(&username), Some(&email), 0).await?;
    let hash = hash_password(password).await?;

    let id: i64 = sqlx::query_scalar(
        "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?) RETURNING id",
    )
    .bind(&username)
    .bind(&email)
    .bind(&hash)
    .fetch_one(&state.pool)
    .await
    .map_err(conflict_on_unique)?;

    let user = find_by_id(&state, id)
        .await?
        .ok_or_else(ApiError::internal)?;
    Ok((StatusCode::CREATED, Json(user_json(&state, &user))))
}

pub async fn login(
    State(state): State<AppState>,
    JsonBody(body): JsonBody<Envelope<LoginUser>>,
) -> ApiResult<Json<Value>> {
    let LoginUser { email, password } = body.user;

    let mut v = Validation::default();
    if is_blank(&email) {
        v.add("email", "can't be blank");
    }
    if password.as_deref().unwrap_or("").is_empty() {
        v.add("password", "can't be blank");
    }
    v.finish()?;

    let invalid = || ApiError::new(StatusCode::UNAUTHORIZED, "credentials", "invalid");
    let user = sqlx::query_as::<_, UserRow>("SELECT * FROM users WHERE email = ?")
        .bind(email.unwrap().trim())
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(invalid)?;

    if !verify_password(password.unwrap(), user.password_hash.clone()).await {
        return Err(invalid());
    }
    Ok(Json(user_json(&state, &user)))
}

pub async fn current(
    State(state): State<AppState>,
    AuthUser(id): AuthUser,
) -> ApiResult<Json<Value>> {
    let user = find_by_id(&state, id)
        .await?
        .ok_or_else(|| ApiError::unauthorized("is invalid"))?;
    Ok(Json(user_json(&state, &user)))
}

pub async fn update(
    State(state): State<AppState>,
    AuthUser(id): AuthUser,
    JsonBody(body): JsonBody<Envelope<UpdateUser>>,
) -> ApiResult<Json<Value>> {
    let mut user = find_by_id(&state, id)
        .await?
        .ok_or_else(|| ApiError::unauthorized("is invalid"))?;
    let changes = body.user;

    let mut v = Validation::default();
    if let Some(username) = &changes.username {
        if is_blank(username) {
            v.add("username", "can't be blank");
        }
    }
    if let Some(email) = &changes.email {
        if is_blank(email) {
            v.add("email", "can't be blank");
        }
    }
    if let Some(password) = &changes.password {
        match password.as_deref() {
            None | Some("") => v.add("password", "can't be blank"),
            Some(p) if p.chars().count() < MIN_PASSWORD_LEN => {
                v.add("password", "is too short (minimum is 8 characters)")
            }
            Some(p) if p.chars().count() > MAX_PASSWORD_LEN => {
                v.add("password", "is too long (maximum is 256 characters)")
            }
            _ => {}
        }
    }
    v.finish()?;

    let new_username = changes.username.flatten().map(|s| s.trim().to_string());
    let new_email = changes.email.flatten().map(|s| s.trim().to_string());
    check_uniqueness(&state, new_username.as_deref(), new_email.as_deref(), id).await?;

    if let Some(u) = new_username {
        user.username = u;
    }
    if let Some(e) = new_email {
        user.email = e;
    }
    if let Some(bio) = changes.bio {
        user.bio = normalize_nullable(bio);
    }
    if let Some(image) = changes.image {
        user.image = normalize_nullable(image);
    }
    if let Some(Some(password)) = changes.password {
        user.password_hash = hash_password(password).await?;
    }

    sqlx::query(
        "UPDATE users SET username = ?, email = ?, password_hash = ?, bio = ?, image = ? WHERE id = ?",
    )
    .bind(&user.username)
    .bind(&user.email)
    .bind(&user.password_hash)
    .bind(&user.bio)
    .bind(&user.image)
    .bind(id)
    .execute(&state.pool)
    .await
    .map_err(conflict_on_unique)?;

    Ok(Json(user_json(&state, &user)))
}
