use axum::body::Bytes;
use axum::extract::{FromRequest, Request};
use serde::de::{Deserialize, DeserializeOwned, Deserializer};

use crate::error::ApiError;

/// JSON body extractor that ignores the content type and reports malformed
/// or mistyped payloads as 422 errors in the RealWorld error format.
pub struct JsonBody<T>(pub T);

impl<S, T> FromRequest<S> for JsonBody<T>
where
    S: Send + Sync,
    T: DeserializeOwned,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        let bytes = Bytes::from_request(req, state)
            .await
            .map_err(|_| ApiError::unprocessable("body", "is invalid"))?;
        serde_json::from_slice(&bytes)
            .map(JsonBody)
            .map_err(|e| ApiError::unprocessable("body", &format!("is invalid: {e}")))
    }
}

/// Deserializes a present field (including an explicit `null`) as `Some(..)`.
/// Combined with `#[serde(default)]` this distinguishes absent from null.
pub fn present<'de, D, T>(d: D) -> Result<Option<T>, D::Error>
where
    D: Deserializer<'de>,
    T: Deserialize<'de>,
{
    T::deserialize(d).map(Some)
}
