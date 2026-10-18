use argon2::{
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use rand_core::OsRng;
use axum::{extract::FromRequestParts, http::request::Parts};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::{error::ApiError, AppState};

#[derive(Serialize, Deserialize)]
struct Claims {
    sub: i64,
    exp: usize,
}

pub fn make_token(secret: &str, user_id: i64) -> String {
    let exp = (chrono::Utc::now().timestamp() + 60 * 60 * 24 * 30) as usize;
    encode(
        &Header::default(),
        &Claims { sub: user_id, exp },
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .expect("jwt encoding")
}

fn verify_token(secret: &str, token: &str) -> Option<i64> {
    decode::<Claims>(
        token,
        &DecodingKey::from_secret(secret.as_bytes()),
        &Validation::default(),
    )
    .ok()
    .map(|d| d.claims.sub)
}

pub fn hash_password(pw: &str) -> String {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(pw.as_bytes(), &salt)
        .expect("password hashing")
        .to_string()
}

pub fn check_password(pw: &str, hash: &str) -> bool {
    PasswordHash::new(hash)
        .map(|h| Argon2::default().verify_password(pw.as_bytes(), &h).is_ok())
        .unwrap_or(false)
}

fn extract_token(parts: &Parts) -> Option<String> {
    let v = parts.headers.get("authorization")?.to_str().ok()?;
    let (scheme, tok) = v.split_once(' ')?;
    if scheme.eq_ignore_ascii_case("token") || scheme.eq_ignore_ascii_case("bearer") {
        Some(tok.trim().to_string())
    } else {
        None
    }
}

/// Required authentication: 401 when the token is missing or invalid.
pub struct AuthUser {
    pub id: i64,
    pub token: String,
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = ApiError;
    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        let token = extract_token(parts).ok_or_else(|| ApiError::unauthorized("token", "is missing"))?;
        let id = verify_token(&state.jwt_secret, &token)
            .ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;
        Ok(AuthUser { id, token })
    }
}

/// Optional authentication: anonymous when no valid token is sent.
pub struct MaybeAuth(pub Option<i64>);

impl FromRequestParts<AppState> for MaybeAuth {
    type Rejection = ApiError;
    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        Ok(MaybeAuth(
            extract_token(parts).and_then(|t| verify_token(&state.jwt_secret, &t)),
        ))
    }
}
