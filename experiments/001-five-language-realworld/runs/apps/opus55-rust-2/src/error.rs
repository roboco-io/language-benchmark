use axum::{
    extract::{rejection::JsonRejection, FromRequest, Request},
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde::de::DeserializeOwned;
use serde_json::json;

/// API error rendered as `{"errors": {"<field>": ["<message>"]}}`.
#[derive(Debug)]
pub struct ApiError {
    status: StatusCode,
    field: String,
    message: String,
}

pub type ApiResult<T> = Result<T, ApiError>;

impl ApiError {
    pub fn new(status: StatusCode, field: impl Into<String>, message: impl Into<String>) -> Self {
        Self { status, field: field.into(), message: message.into() }
    }

    pub fn validation(field: &str, message: &str) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, field, message)
    }

    pub fn blank(field: &str) -> Self {
        Self::validation(field, "can't be blank")
    }

    pub fn taken(field: &str) -> Self {
        Self::new(StatusCode::CONFLICT, field, "has already been taken")
    }

    pub fn not_found(entity: &str) -> Self {
        Self::new(StatusCode::NOT_FOUND, entity, "not found")
    }

    pub fn forbidden(entity: &str) -> Self {
        Self::new(StatusCode::FORBIDDEN, entity, "forbidden")
    }

    pub fn unauthorized(field: &str, message: &str) -> Self {
        Self::new(StatusCode::UNAUTHORIZED, field, message)
    }

    pub fn internal(message: impl std::fmt::Display) -> Self {
        eprintln!("internal error: {message}");
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "server", "internal error")
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = json!({ "errors": { self.field: [self.message] } });
        (self.status, Json(body)).into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(err: sqlx::Error) -> Self {
        Self::internal(err)
    }
}

/// JSON body extractor that reports malformed bodies as 422 in the API error format.
pub struct ApiJson<T>(pub T);

impl<S, T> FromRequest<S> for ApiJson<T>
where
    T: DeserializeOwned,
    S: Send + Sync,
{
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        match Json::<T>::from_request(req, state).await {
            Ok(Json(value)) => Ok(Self(value)),
            Err(rejection) => Err(match rejection {
                JsonRejection::JsonDataError(e) => ApiError::validation("body", &e.body_text()),
                other => ApiError::new(other.status(), "body", other.body_text()),
            }),
        }
    }
}

/// Deserializes a field so that "absent" (`None`) and "explicit null" (`Some(None)`)
/// can be told apart. Use together with `#[serde(default)]`.
pub fn double_option<'de, D, T>(de: D) -> Result<Option<Option<T>>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: serde::Deserialize<'de>,
{
    serde::Deserialize::deserialize(de).map(Some)
}
