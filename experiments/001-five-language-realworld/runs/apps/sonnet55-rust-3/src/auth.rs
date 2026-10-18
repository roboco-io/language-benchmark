use crate::{error::ApiError, AppState};
use argon2::{password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString}, Argon2};
use axum::{extract::FromRequestParts, http::{header, request::Parts}};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
struct Claims {
    sub: i64,
    exp: usize,
}

pub fn make_token(secret: &str, user_id: i64) -> String {
    let exp = (chrono::Utc::now().timestamp() + 60 * 60 * 24 * 30) as usize;
    encode(&Header::default(), &Claims { sub: user_id, exp }, &EncodingKey::from_secret(secret.as_bytes()))
        .expect("jwt encoding")
}

pub async fn hash_password(pw: String) -> String {
    tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default().hash_password(pw.as_bytes(), &salt).expect("hash").to_string()
    })
    .await
    .expect("hash task")
}

pub async fn verify_password(pw: String, hash: String) -> bool {
    tokio::task::spawn_blocking(move || match PasswordHash::new(&hash) {
        Ok(h) => Argon2::default().verify_password(pw.as_bytes(), &h).is_ok(),
        Err(_) => false,
    })
    .await
    .unwrap_or(false)
}

fn extract_token(parts: &Parts) -> Option<String> {
    let v = parts.headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    let t = v.strip_prefix("Token ").or_else(|| v.strip_prefix("Bearer "))?;
    Some(t.trim().to_string())
}

fn user_from_token(state: &AppState, token: &str) -> Result<i64, ApiError> {
    decode::<Claims>(token, &DecodingKey::from_secret(state.jwt_secret.as_bytes()), &Validation::default())
        .map(|d| d.claims.sub)
        .map_err(|_| ApiError::unauthorized("token", "is invalid"))
}

/// Required authentication: 401 when the token is missing or invalid.
pub struct AuthUser(pub i64);

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = ApiError;
    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        let token = extract_token(parts).ok_or_else(|| ApiError::unauthorized("token", "is missing"))?;
        Ok(AuthUser(user_from_token(state, &token)?))
    }
}

/// Optional authentication: anonymous when no valid token is supplied.
pub struct MaybeUser(pub Option<i64>);

impl FromRequestParts<AppState> for MaybeUser {
    type Rejection = ApiError;
    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        Ok(MaybeUser(extract_token(parts).and_then(|t| user_from_token(state, &t).ok())))
    }
}
