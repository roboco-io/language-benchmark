use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

/// An API error rendered as `{"errors": {"<field>": ["<message>"]}}`.
#[derive(Debug)]
pub struct AppError {
    status: StatusCode,
    field: String,
    message: String,
}

pub type AppResult<T> = Result<T, AppError>;

impl AppError {
    pub fn new(status: StatusCode, field: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            status,
            field: field.into(),
            message: message.into(),
        }
    }

    pub fn blank(field: &str) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, field, "can't be blank")
    }

    pub fn invalid(field: &str, message: &str) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, field, message)
    }

    pub fn taken(field: &str) -> Self {
        Self::new(StatusCode::CONFLICT, field, "has already been taken")
    }

    pub fn not_found(field: &str) -> Self {
        Self::new(StatusCode::NOT_FOUND, field, "not found")
    }

    pub fn forbidden(field: &str) -> Self {
        Self::new(StatusCode::FORBIDDEN, field, "forbidden")
    }

    pub fn unauthorized(field: &str, message: &str) -> Self {
        Self::new(StatusCode::UNAUTHORIZED, field, message)
    }

    pub fn internal(err: impl std::fmt::Display) -> Self {
        tracing::error!("internal error: {err}");
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "server", "internal error")
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        Self::internal(err)
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let body = json!({ "errors": { self.field: [self.message] } });
        (self.status, Json(body)).into_response()
    }
}
