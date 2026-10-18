use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{extract::FromRequestParts, http::request::Parts};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::{error::AppError, AppState};

const TOKEN_TTL_SECONDS: i64 = 60 * 60 * 24 * 30;

#[derive(Serialize, Deserialize)]
struct Claims {
    sub: i64,
    exp: i64,
}

pub fn create_token(state: &AppState, user_id: i64) -> Result<String, AppError> {
    let claims = Claims {
        sub: user_id,
        exp: chrono::Utc::now().timestamp() + TOKEN_TTL_SECONDS,
    };
    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(state.jwt_secret.as_bytes()),
    )
    .map_err(AppError::internal)
}

fn decode_token(state: &AppState, token: &str) -> Option<i64> {
    decode::<Claims>(
        token,
        &DecodingKey::from_secret(state.jwt_secret.as_bytes()),
        &Validation::default(),
    )
    .ok()
    .map(|data| data.claims.sub)
}

/// Hashes a password on the blocking thread pool because Argon2 is CPU-bound.
pub async fn hash_password(password: String) -> Result<String, AppError> {
    tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|hash| hash.to_string())
            .map_err(AppError::internal)
    })
    .await
    .map_err(AppError::internal)?
}

pub async fn verify_password(password: String, hash: String) -> Result<bool, AppError> {
    tokio::task::spawn_blocking(move || {
        let parsed = PasswordHash::new(&hash).map_err(AppError::internal)?;
        Ok(Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .is_ok())
    })
    .await
    .map_err(AppError::internal)?
}

/// An authenticated user. Rejects the request with 401 when the token is missing or invalid.
pub struct AuthUser {
    pub id: i64,
    pub token: String,
}

/// An optionally authenticated user. A missing token yields `None`; an invalid token is rejected.
pub struct MaybeUser(pub Option<AuthUser>);

impl MaybeUser {
    pub fn id(&self) -> Option<i64> {
        self.0.as_ref().map(|user| user.id)
    }
}

fn extract_token(parts: &Parts) -> Option<String> {
    let value = parts.headers.get(axum::http::header::AUTHORIZATION)?.to_str().ok()?;
    let token = value
        .strip_prefix("Token ")
        .or_else(|| value.strip_prefix("Bearer "))?
        .trim();
    (!token.is_empty()).then(|| token.to_string())
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        match MaybeUser::from_request_parts(parts, state).await?.0 {
            Some(user) => Ok(user),
            None => Err(AppError::unauthorized("token", "is missing")),
        }
    }
}

impl FromRequestParts<AppState> for MaybeUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        let Some(token) = extract_token(parts) else {
            return Ok(MaybeUser(None));
        };
        let id = decode_token(state, &token).ok_or_else(|| AppError::unauthorized("token", "is invalid"))?;
        // A token for a deleted user must not authenticate.
        let exists: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE id = ?")
            .bind(id)
            .fetch_optional(&state.pool)
            .await?;
        match exists {
            Some(id) => Ok(MaybeUser(Some(AuthUser { id, token }))),
            None => Err(AppError::unauthorized("token", "is invalid")),
        }
    }
}
