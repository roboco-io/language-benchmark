use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::{json, Map, Value};

/// API error rendered as `{"errors": {field: [messages]}}`.
pub struct ApiError {
    status: StatusCode,
    errors: Map<String, Value>,
}

impl ApiError {
    pub fn new(status: StatusCode, field: &str, msg: &str) -> Self {
        let mut errors = Map::new();
        errors.insert(field.to_string(), json!([msg]));
        ApiError { status, errors }
    }
    pub fn unprocessable(field: &str, msg: &str) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, field, msg)
    }
    pub fn blank(field: &str) -> Self {
        Self::unprocessable(field, "can't be blank")
    }
    pub fn unauthorized(field: &str, msg: &str) -> Self {
        Self::new(StatusCode::UNAUTHORIZED, field, msg)
    }
    pub fn forbidden(field: &str) -> Self {
        Self::new(StatusCode::FORBIDDEN, field, "forbidden")
    }
    pub fn not_found(field: &str) -> Self {
        Self::new(StatusCode::NOT_FOUND, field, "not found")
    }
    pub fn conflict(field: &str) -> Self {
        Self::new(StatusCode::CONFLICT, field, "has already been taken")
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(e: sqlx::Error) -> Self {
        eprintln!("database error: {e}");
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "server", "internal error")
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(json!({ "errors": self.errors }))).into_response()
    }
}

pub type ApiResult<T> = Result<T, ApiError>;
