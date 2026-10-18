use std::collections::BTreeMap;

use axum::Json;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

/// API error rendered as `{"errors": {"<field>": ["<message>"]}}`.
#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub errors: BTreeMap<String, Vec<String>>,
}

pub type ApiResult<T> = Result<T, ApiError>;

impl ApiError {
    pub fn new(status: StatusCode, field: &str, message: &str) -> Self {
        let mut errors = BTreeMap::new();
        errors.insert(field.to_string(), vec![message.to_string()]);
        Self { status, errors }
    }

    pub fn unauthorized(message: &str) -> Self {
        Self::new(StatusCode::UNAUTHORIZED, "token", message)
    }

    pub fn not_found(entity: &str) -> Self {
        Self::new(StatusCode::NOT_FOUND, entity, "not found")
    }

    pub fn forbidden(entity: &str) -> Self {
        Self::new(StatusCode::FORBIDDEN, entity, "forbidden")
    }

    pub fn unprocessable(field: &str, message: &str) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, field, message)
    }

    pub fn internal() -> Self {
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "server",
            "internal error",
        )
    }
}

/// Collects field validation errors and turns them into a 422 response.
#[derive(Default)]
pub struct Validation {
    errors: BTreeMap<String, Vec<String>>,
}

impl Validation {
    pub fn add(&mut self, field: &str, message: &str) {
        self.errors
            .entry(field.to_string())
            .or_default()
            .push(message.to_string());
    }

    pub fn finish(self) -> ApiResult<()> {
        if self.errors.is_empty() {
            Ok(())
        } else {
            Err(ApiError {
                status: StatusCode::UNPROCESSABLE_ENTITY,
                errors: self.errors,
            })
        }
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(err: sqlx::Error) -> Self {
        tracing::error!("database error: {err}");
        Self::internal()
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(json!({ "errors": self.errors }))).into_response()
    }
}
