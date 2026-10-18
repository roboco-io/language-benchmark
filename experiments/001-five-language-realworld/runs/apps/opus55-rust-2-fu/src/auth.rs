use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{extract::FromRequestParts, http::header::AUTHORIZATION, http::request::Parts};
use jsonwebtoken::{DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::{
    error::{ApiError, ApiResult},
    AppState,
};

const TOKEN_TTL_SECS: i64 = 60 * 60 * 24 * 30;

#[derive(Serialize, Deserialize)]
struct Claims {
    sub: i64,
    exp: i64,
}

pub fn create_token(state: &AppState, user_id: i64) -> ApiResult<String> {
    let claims = Claims { sub: user_id, exp: chrono::Utc::now().timestamp() + TOKEN_TTL_SECS };
    jsonwebtoken::encode(&Header::default(), &claims, &EncodingKey::from_secret(state.jwt_secret.as_bytes()))
        .map_err(ApiError::internal)
}

fn decode_token(state: &AppState, token: &str) -> Option<i64> {
    jsonwebtoken::decode::<Claims>(
        token,
        &DecodingKey::from_secret(state.jwt_secret.as_bytes()),
        &Validation::default(),
    )
    .ok()
    .map(|data| data.claims.sub)
}

pub async fn hash_password(password: String) -> ApiResult<String> {
    tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default().hash_password(password.as_bytes(), &salt).map(|h| h.to_string())
    })
    .await
    .map_err(ApiError::internal)?
    .map_err(ApiError::internal)
}

pub async fn verify_password(password: String, hash: String) -> ApiResult<bool> {
    tokio::task::spawn_blocking(move || {
        PasswordHash::new(&hash)
            .map(|parsed| Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok())
            .unwrap_or(false)
    })
    .await
    .map_err(ApiError::internal)
}

/// An authenticated request. Rejects with 401 when the token is missing or invalid.
pub struct AuthUser {
    pub id: i64,
}

/// A request that may or may not carry a token. A present but invalid token is still rejected.
pub struct MaybeAuthUser(pub Option<AuthUser>);

impl MaybeAuthUser {
    /// The viewer's user id, or 0 (never a real id) for anonymous requests.
    pub fn viewer_id(&self) -> i64 {
        self.0.as_ref().map_or(0, |u| u.id)
    }
}

fn extract(parts: &Parts, state: &AppState) -> ApiResult<Option<AuthUser>> {
    let Some(header) = parts.headers.get(AUTHORIZATION) else {
        return Ok(None);
    };
    let header = header.to_str().map_err(|_| ApiError::unauthorized("token", "is invalid"))?;
    let token = header
        .strip_prefix("Token ")
        .or_else(|| header.strip_prefix("Bearer "))
        .ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;
    let id = decode_token(state, token.trim()).ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;
    Ok(Some(AuthUser { id }))
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        extract(parts, state)?.ok_or_else(|| ApiError::unauthorized("token", "is missing"))
    }
}

impl FromRequestParts<AppState> for MaybeAuthUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        extract(parts, state).map(MaybeAuthUser)
    }
}
