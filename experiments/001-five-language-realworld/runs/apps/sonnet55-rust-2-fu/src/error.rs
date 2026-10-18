use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

/// API error rendered as `{"errors": {"<field>": ["<message>"]}}`.
#[derive(Debug)]
pub enum ApiError {
    Unprocessable(Vec<(&'static str, String)>),
    Conflict(&'static str, &'static str),
    Unauthorized(&'static str, &'static str),
    Forbidden(&'static str),
    NotFound(&'static str),
    Internal(String),
}

impl ApiError {
    pub fn blank(field: &'static str) -> Self {
        ApiError::Unprocessable(vec![(field, "can't be blank".into())])
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(e: sqlx::Error) -> Self {
        ApiError::Internal(e.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, errors) = match self {
            ApiError::Unprocessable(list) => {
                let mut map = serde_json::Map::new();
                for (field, msg) in list {
                    let entry = map.entry(field).or_insert_with(|| json!([]));
                    entry.as_array_mut().unwrap().push(json!(msg));
                }
                (StatusCode::UNPROCESSABLE_ENTITY, serde_json::Value::Object(map))
            }
            ApiError::Conflict(f, m) => (StatusCode::CONFLICT, json!({ f: [m] })),
            ApiError::Unauthorized(f, m) => (StatusCode::UNAUTHORIZED, json!({ f: [m] })),
            ApiError::Forbidden(f) => (StatusCode::FORBIDDEN, json!({ f: ["forbidden"] })),
            ApiError::NotFound(f) => (StatusCode::NOT_FOUND, json!({ f: ["not found"] })),
            ApiError::Internal(msg) => {
                eprintln!("internal error: {msg}");
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    json!({ "server": ["internal error"] }),
                )
            }
        };
        (status, Json(json!({ "errors": errors }))).into_response()
    }
}

pub type ApiResult<T> = Result<T, ApiError>;
