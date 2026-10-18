use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use jsonwebtoken::{DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::error::ApiError;

const TOKEN_TTL_SECS: i64 = 60 * 60 * 24 * 30;

pub struct JwtKeys {
    encoding: EncodingKey,
    decoding: DecodingKey,
}

#[derive(Serialize, Deserialize)]
struct Claims {
    sub: i64,
    exp: i64,
}

impl JwtKeys {
    pub fn new(secret: &[u8]) -> Self {
        Self {
            encoding: EncodingKey::from_secret(secret),
            decoding: DecodingKey::from_secret(secret),
        }
    }

    pub fn issue(&self, user_id: i64) -> String {
        let claims = Claims {
            sub: user_id,
            exp: chrono::Utc::now().timestamp() + TOKEN_TTL_SECS,
        };
        jsonwebtoken::encode(&Header::default(), &claims, &self.encoding)
            .expect("JWT encoding cannot fail with HS256")
    }

    fn verify(&self, token: &str) -> Option<i64> {
        jsonwebtoken::decode::<Claims>(token, &self.decoding, &Validation::default())
            .ok()
            .map(|data| data.claims.sub)
    }
}

/// Reads the user id from `Authorization: Token <jwt>` (or `Bearer <jwt>`).
/// Returns `Ok(None)` when the header is absent.
fn user_from_parts(parts: &Parts, state: &AppState) -> Result<Option<i64>, ApiError> {
    let Some(value) = parts.headers.get(AUTHORIZATION) else {
        return Ok(None);
    };
    let value = value
        .to_str()
        .map_err(|_| ApiError::unauthorized("is invalid"))?;
    let token = value
        .strip_prefix("Token ")
        .or_else(|| value.strip_prefix("Bearer "))
        .ok_or_else(|| ApiError::unauthorized("is invalid"))?
        .trim();
    state
        .jwt
        .verify(token)
        .map(Some)
        .ok_or_else(|| ApiError::unauthorized("is invalid"))
}

/// Extractor for endpoints that require authentication.
pub struct AuthUser(pub i64);

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        user_from_parts(parts, state)?
            .map(AuthUser)
            .ok_or_else(|| ApiError::unauthorized("is missing"))
    }
}

/// Extractor for endpoints where authentication is optional.
pub struct MaybeUser(pub Option<i64>);

impl FromRequestParts<AppState> for MaybeUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, ApiError> {
        user_from_parts(parts, state).map(MaybeUser)
    }
}
