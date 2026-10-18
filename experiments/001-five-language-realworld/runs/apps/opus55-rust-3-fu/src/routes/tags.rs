use axum::Json;
use axum::extract::State;
use serde_json::{Value, json};

use crate::AppState;
use crate::error::ApiResult;

/// Lists tags that are attached to at least one article.
pub async fn list(State(state): State<AppState>) -> ApiResult<Json<Value>> {
    let tags: Vec<String> = sqlx::query_scalar(
        "SELECT t.name FROM tags t \
         WHERE EXISTS(SELECT 1 FROM article_tags at WHERE at.tag_id = t.id) \
         ORDER BY t.name",
    )
    .fetch_all(&state.pool)
    .await?;
    Ok(Json(json!({ "tags": tags })))
}
