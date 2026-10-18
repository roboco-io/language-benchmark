use axum::{
    extract::{Path, State},
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::{
    articles::find_article,
    auth::{AuthUser, MaybeAuthUser},
    db,
    error::{ApiError, ApiJson, ApiResult},
    profiles::Profile,
    AppState,
};

/// `?1` is the viewer id (0 when anonymous).
const COMMENT_SELECT: &str = "
SELECT c.id, c.body, c.created_at, c.updated_at, u.id AS author_id, u.username, u.bio, u.image,
       EXISTS(SELECT 1 FROM follows fo WHERE fo.follower_id = ?1 AND fo.followee_id = u.id) AS following
FROM comments c JOIN users u ON u.id = c.author_id";

#[derive(sqlx::FromRow)]
struct CommentRow {
    id: i64,
    body: String,
    created_at: String,
    updated_at: String,
    author_id: i64,
    username: String,
    bio: Option<String>,
    image: Option<String>,
    following: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CommentView {
    id: i64,
    body: String,
    created_at: String,
    updated_at: String,
    author: Profile,
}

impl From<CommentRow> for CommentView {
    fn from(r: CommentRow) -> Self {
        Self {
            id: r.id,
            body: r.body,
            created_at: r.created_at,
            updated_at: r.updated_at,
            author: Profile {
                id: r.author_id,
                username: r.username,
                bio: r.bio,
                image: r.image,
                following: r.following,
            },
        }
    }
}

pub async fn list_comments(
    State(state): State<AppState>,
    viewer: MaybeAuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let (article_id, _) = find_article(&state, &slug).await?;
    let rows: Vec<CommentRow> = sqlx::query_as(&format!("{COMMENT_SELECT} WHERE c.article_id = ?2 ORDER BY c.id"))
        .bind(viewer.viewer_id())
        .bind(article_id)
        .fetch_all(&state.pool)
        .await?;
    let comments: Vec<CommentView> = rows.into_iter().map(Into::into).collect();
    Ok(Json(json!({ "comments": comments })))
}

#[derive(Deserialize)]
pub struct CreateRequest {
    comment: NewComment,
}

#[derive(Deserialize)]
struct NewComment {
    body: Option<String>,
}

pub async fn add_comment(
    State(state): State<AppState>,
    user: AuthUser,
    Path(slug): Path<String>,
    ApiJson(req): ApiJson<CreateRequest>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let (article_id, _) = find_article(&state, &slug).await?;
    let body = req.comment.body.filter(|b| !b.trim().is_empty()).ok_or_else(|| ApiError::blank("body"))?;
    let now = db::now();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO comments (body, article_id, author_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(&body)
    .bind(article_id)
    .bind(user.id)
    .bind(&now)
    .bind(&now)
    .fetch_one(&state.pool)
    .await?;

    let row: CommentRow = sqlx::query_as(&format!("{COMMENT_SELECT} WHERE c.id = ?2"))
        .bind(user.id)
        .bind(id)
        .fetch_one(&state.pool)
        .await?;
    Ok((StatusCode::CREATED, Json(json!({ "comment": CommentView::from(row) }))))
}

pub async fn delete_comment(
    State(state): State<AppState>,
    user: AuthUser,
    Path((slug, comment_id)): Path<(String, i64)>,
) -> ApiResult<StatusCode> {
    let (article_id, _) = find_article(&state, &slug).await?;
    let author_id: i64 = sqlx::query_scalar("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
        .bind(comment_id)
        .bind(article_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| ApiError::not_found("comment"))?;
    if author_id != user.id {
        return Err(ApiError::forbidden("comment"));
    }
    sqlx::query("DELETE FROM comments WHERE id = ?").bind(comment_id).execute(&state.pool).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn update_comment(
    State(state): State<AppState>,
    user: AuthUser,
    Path((slug, comment_id)): Path<(String, i64)>,
    ApiJson(req): ApiJson<CreateRequest>,
) -> ApiResult<Json<Value>> {
    let (article_id, _) = find_article(&state, &slug).await?;
    let author_id: i64 = sqlx::query_scalar("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
        .bind(comment_id)
        .bind(article_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| ApiError::not_found("comment"))?;
    if author_id != user.id {
        return Err(ApiError::forbidden("comment"));
    }
    let body = req.comment.body.filter(|b| !b.trim().is_empty()).ok_or_else(|| ApiError::blank("body"))?;
    sqlx::query("UPDATE comments SET body = ?, updated_at = ? WHERE id = ?")
        .bind(&body)
        .bind(db::now())
        .bind(comment_id)
        .execute(&state.pool)
        .await?;

    let row: CommentRow = sqlx::query_as(&format!("{COMMENT_SELECT} WHERE c.id = ?2"))
        .bind(user.id)
        .bind(comment_id)
        .fetch_one(&state.pool)
        .await?;
    Ok(Json(json!({ "comment": CommentView::from(row) })))
}
