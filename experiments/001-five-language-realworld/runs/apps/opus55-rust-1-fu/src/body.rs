use axum::{
    body::Bytes,
    extract::{FromRequest, Request},
};
use serde_json::{Map, Value};

use crate::error::AppError;

/// A JSON request body parsed leniently (no Content-Type check) so that
/// "absent" and "null" fields can be told apart by the handlers.
pub struct JsonBody(pub Value);

impl<S: Send + Sync> FromRequest<S> for JsonBody {
    type Rejection = AppError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        let bytes = Bytes::from_request(req, state)
            .await
            .map_err(|_| AppError::invalid("body", "is invalid"))?;
        if bytes.iter().all(u8::is_ascii_whitespace) {
            return Ok(JsonBody(Value::Null));
        }
        serde_json::from_slice(&bytes)
            .map(JsonBody)
            .map_err(|_| AppError::invalid("body", "is invalid"))
    }
}

impl JsonBody {
    /// Returns the object nested under `key` (e.g. `{"user": {...}}`), or an empty object.
    pub fn object(&self, key: &str) -> Map<String, Value> {
        match self.0.get(key) {
            Some(Value::Object(map)) => map.clone(),
            _ => Map::new(),
        }
    }
}

/// The state of a single field in a JSON object.
pub enum Field {
    Absent,
    Null,
    Str(String),
    Other(Value),
}

pub fn field(map: &Map<String, Value>, key: &str) -> Field {
    match map.get(key) {
        None => Field::Absent,
        Some(Value::Null) => Field::Null,
        Some(Value::String(s)) => Field::Str(s.clone()),
        Some(other) => Field::Other(other.clone()),
    }
}

/// Reads a required, non-blank string field.
pub fn required_str(map: &Map<String, Value>, key: &str) -> Result<String, AppError> {
    match field(map, key) {
        Field::Str(s) if !s.trim().is_empty() => Ok(s),
        Field::Other(_) => Err(AppError::invalid(key, "must be a string")),
        _ => Err(AppError::blank(key)),
    }
}

/// Reads an optional field that, when present, must be a non-blank string.
pub fn optional_nonblank_str(map: &Map<String, Value>, key: &str) -> Result<Option<String>, AppError> {
    match field(map, key) {
        Field::Absent => Ok(None),
        Field::Str(s) if !s.trim().is_empty() => Ok(Some(s)),
        Field::Other(_) => Err(AppError::invalid(key, "must be a string")),
        _ => Err(AppError::blank(key)),
    }
}

/// Reads an optional nullable string field. Empty strings are normalized to null.
/// Returns `None` when absent and `Some(None)` when it should be cleared.
pub fn optional_nullable_str(
    map: &Map<String, Value>,
    key: &str,
) -> Result<Option<Option<String>>, AppError> {
    match field(map, key) {
        Field::Absent => Ok(None),
        Field::Null => Ok(Some(None)),
        Field::Str(s) if s.trim().is_empty() => Ok(Some(None)),
        Field::Str(s) => Ok(Some(Some(s))),
        Field::Other(_) => Err(AppError::invalid(key, "must be a string")),
    }
}
