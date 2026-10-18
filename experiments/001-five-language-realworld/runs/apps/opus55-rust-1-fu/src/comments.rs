use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use serde_json::{json, Value};

use crate::{
    articles::article_id,
    auth::{AuthUser, MaybeUser},
    body::{required_str, JsonBody},
    error::{AppError, AppResult},
    now_timestamp, AppState,
};

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
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "body": self.body,
            "author": {
                "username": self.username,
                "bio": self.bio,
                "image": self.image,
                "following": self.following,
            },
        })
    }
}

const SELECT_COMMENTS: &str = "SELECT c.id, c.body, c.created_at, c.updated_at, \
     u.username, u.bio, u.image, \
     EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS following \
     FROM comments c JOIN users u ON u.id = c.author_id";

pub async fn list_comments(
    State(state): State<AppState>,
    viewer: MaybeUser,
    Path(slug): Path<String>,
) -> AppResult<Json<Value>> {
    let article_id = article_id(&state, &slug).await?;
    let rows: Vec<CommentRow> =
        sqlx::query_as(&format!("{SELECT_COMMENTS} WHERE c.article_id = ? ORDER BY c.id"))
            .bind(viewer.id())
            .bind(article_id)
            .fetch_all(&state.pool)
            .await?;
    let comments: Vec<Value> = rows.iter().map(CommentRow::to_json).collect();
    Ok(Json(json!({ "comments": comments })))
}

pub async fn add_comment(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
    body: JsonBody,
) -> AppResult<(StatusCode, Json<Value>)> {
    let article_id = article_id(&state, &slug).await?;
    let input = body.object("comment");
    let comment_body = required_str(&input, "body")?;
    let now = now_timestamp();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(article_id)
    .bind(auth.id)
    .bind(&comment_body)
    .bind(&now)
    .bind(&now)
    .fetch_one(&state.pool)
    .await?;
    let row: CommentRow = sqlx::query_as(&format!("{SELECT_COMMENTS} WHERE c.id = ?"))
        .bind(auth.id)
        .bind(id)
        .fetch_one(&state.pool)
        .await?;
    Ok((StatusCode::CREATED, Json(json!({ "comment": row.to_json() }))))
}

/// Loads a comment on the article `slug` that `user_id` may modify.
/// Returns the comment's id and current `updated_at`, or 404/403 errors.
async fn load_owned_comment(
    state: &AppState,
    slug: &str,
    comment_id: &str,
    user_id: i64,
) -> AppResult<(i64, String)> {
    let article_id = article_id(state, slug).await?;
    let not_found = || AppError::not_found("comment");
    let comment_id: i64 = comment_id.parse().map_err(|_| not_found())?;
    let (author_id, updated_at): (i64, String) = sqlx::query_as(
        "SELECT author_id, updated_at FROM comments WHERE id = ? AND article_id = ?",
    )
    .bind(comment_id)
    .bind(article_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(not_found)?;
    if author_id != user_id {
        return Err(AppError::forbidden("comment"));
    }
    Ok((comment_id, updated_at))
}

pub async fn update_comment(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((slug, comment_id)): Path<(String, String)>,
    body: JsonBody,
) -> AppResult<Json<Value>> {
    let (comment_id, updated_at) = load_owned_comment(&state, &slug, &comment_id, auth.id).await?;
    let input = body.object("comment");
    let comment_body = required_str(&input, "body")?;
    // Guarantee updatedAt moves forward even for updates within the same millisecond.
    let mut now = now_timestamp();
    if now <= updated_at {
        now = crate::bump_timestamp(&updated_at);
    }
    sqlx::query("UPDATE comments SET body = ?, updated_at = ? WHERE id = ?")
        .bind(&comment_body)
        .bind(&now)
        .bind(comment_id)
        .execute(&state.pool)
        .await?;
    let row: CommentRow = sqlx::query_as(&format!("{SELECT_COMMENTS} WHERE c.id = ?"))
        .bind(auth.id)
        .bind(comment_id)
        .fetch_one(&state.pool)
        .await?;
    Ok(Json(json!({ "comment": row.to_json() })))
}

pub async fn delete_comment(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((slug, comment_id)): Path<(String, String)>,
) -> AppResult<StatusCode> {
    let (comment_id, _) = load_owned_comment(&state, &slug, &comment_id, auth.id).await?;
    sqlx::query("DELETE FROM comments WHERE id = ?")
        .bind(comment_id)
        .execute(&state.pool)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
