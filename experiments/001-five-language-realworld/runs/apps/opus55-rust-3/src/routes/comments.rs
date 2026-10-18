use axum::Json;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use serde::Deserialize;
use serde_json::{Value, json};

use super::articles::find_article;
use crate::AppState;
use crate::auth::{AuthUser, MaybeUser};
use crate::db::now;
use crate::error::{ApiError, ApiResult};
use crate::extract::JsonBody;

const COMMENT_SELECT: &str = "SELECT c.id, c.body, c.created_at, c.updated_at, \
    u.username, u.bio, u.image, \
    EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ?1 AND f.followee_id = c.author_id) AS following \
    FROM comments c JOIN users u ON u.id = c.author_id";

#[derive(sqlx::FromRow)]
struct CommentRow {
    id: i64,
    body: String,
    created_at: String,
    updated_at: String,
    username: String,
    bio: Option<String>,
    image: Option<String>,
    following: bool,
}

impl CommentRow {
    fn to_json(&self) -> Value {
        json!({
            "id": self.id,
            "body": self.body,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "author": {
                "username": self.username,
                "bio": self.bio,
                "image": self.image,
                "following": self.following,
            }
        })
    }
}

#[derive(Deserialize)]
pub struct Envelope {
    comment: NewComment,
}

#[derive(Deserialize)]
pub struct NewComment {
    body: Option<String>,
}

pub async fn list(
    State(state): State<AppState>,
    MaybeUser(viewer): MaybeUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let (article_id, _) = find_article(&state, &slug).await?;
    let sql = format!("{COMMENT_SELECT} WHERE c.article_id = ?2 ORDER BY c.id ASC");
    let rows = sqlx::query_as::<_, CommentRow>(&sql)
        .bind(viewer)
        .bind(article_id)
        .fetch_all(&state.pool)
        .await?;
    let comments: Vec<Value> = rows.iter().map(CommentRow::to_json).collect();
    Ok(Json(json!({ "comments": comments })))
}

pub async fn create(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path(slug): Path<String>,
    JsonBody(body): JsonBody<Envelope>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let text = body.comment.body.unwrap_or_default();
    if text.trim().is_empty() {
        return Err(ApiError::unprocessable("body", "can't be blank"));
    }
    let (article_id, _) = find_article(&state, &slug).await?;
    let ts = now();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(article_id)
    .bind(me)
    .bind(&text)
    .bind(&ts)
    .bind(&ts)
    .fetch_one(&state.pool)
    .await?;

    let sql = format!("{COMMENT_SELECT} WHERE c.id = ?2");
    let row = sqlx::query_as::<_, CommentRow>(&sql)
        .bind(me)
        .bind(id)
        .fetch_one(&state.pool)
        .await?;
    Ok((
        StatusCode::CREATED,
        Json(json!({ "comment": row.to_json() })),
    ))
}

pub async fn remove(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path((slug, id)): Path<(String, String)>,
) -> ApiResult<StatusCode> {
    let (article_id, _) = find_article(&state, &slug).await?;
    let id: i64 = id.parse().map_err(|_| ApiError::not_found("comment"))?;
    let author_id: i64 =
        sqlx::query_scalar("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
            .bind(id)
            .bind(article_id)
            .fetch_optional(&state.pool)
            .await?
            .ok_or_else(|| ApiError::not_found("comment"))?;
    if author_id != me {
        return Err(ApiError::forbidden("comment"));
    }
    sqlx::query("DELETE FROM comments WHERE id = ?")
        .bind(id)
        .execute(&state.pool)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
