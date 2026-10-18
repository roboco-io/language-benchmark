use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sqlx::{Sqlite, Transaction};

use crate::{
    auth::{AuthUser, MaybeAuthUser},
    db,
    error::{double_option, ApiError, ApiJson, ApiResult},
    profiles::Profile,
    AppState,
};

/// Columns selected for an article view. `?1` is the viewer id (0 when anonymous).
const ARTICLE_SELECT: &str = "
SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at,
       u.id AS author_id, u.username, u.bio, u.image,
       EXISTS(SELECT 1 FROM follows fo WHERE fo.follower_id = ?1 AND fo.followee_id = u.id) AS following,
       EXISTS(SELECT 1 FROM favorites f WHERE f.user_id = ?1 AND f.article_id = a.id) AS favorited,
       (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count,
       (SELECT json_group_array(name) FROM (
            SELECT t.name FROM article_tags at JOIN tags t ON t.id = at.tag_id
            WHERE at.article_id = a.id ORDER BY at.position)) AS tags
FROM articles a JOIN users u ON u.id = a.author_id";

/// Filter shared by the list and count queries.
/// `?1` viewer, `?2` tag, `?3` author, `?4` favorited-by username, `?5` feed flag.
const LIST_FILTER: &str = "
WHERE (?2 IS NULL OR EXISTS(SELECT 1 FROM article_tags at JOIN tags t ON t.id = at.tag_id
                            WHERE at.article_id = a.id AND t.name = ?2))
  AND (?3 IS NULL OR u.username = ?3)
  AND (?4 IS NULL OR EXISTS(SELECT 1 FROM favorites f JOIN users fu ON fu.id = f.user_id
                            WHERE f.article_id = a.id AND fu.username = ?4))
  AND (?5 = 0 OR EXISTS(SELECT 1 FROM follows fo WHERE fo.follower_id = ?1 AND fo.followee_id = a.author_id))";

#[derive(sqlx::FromRow)]
struct ArticleRow {
    id: i64,
    slug: String,
    title: String,
    description: String,
    body: String,
    created_at: String,
    updated_at: String,
    author_id: i64,
    username: String,
    bio: Option<String>,
    image: Option<String>,
    following: bool,
    favorited: bool,
    favorites_count: i64,
    tags: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ArticleView {
    slug: String,
    title: String,
    description: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    body: Option<String>,
    tag_list: Vec<String>,
    created_at: String,
    updated_at: String,
    favorited: bool,
    favorites_count: i64,
    author: Profile,
}

impl ArticleRow {
    fn into_view(self, with_body: bool) -> ArticleView {
        ArticleView {
            slug: self.slug,
            title: self.title,
            description: self.description,
            body: with_body.then_some(self.body),
            tag_list: serde_json::from_str(&self.tags).unwrap_or_default(),
            created_at: self.created_at,
            updated_at: self.updated_at,
            favorited: self.favorited,
            favorites_count: self.favorites_count,
            author: Profile {
                id: self.author_id,
                username: self.username,
                bio: self.bio,
                image: self.image,
                following: self.following,
            },
        }
    }
}

async fn load_by_slug(state: &AppState, slug: &str, viewer_id: i64) -> ApiResult<ArticleRow> {
    sqlx::query_as(&format!("{ARTICLE_SELECT} WHERE a.slug = ?2"))
        .bind(viewer_id)
        .bind(slug)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

/// Looks up `(id, author_id)` for a slug, or 404.
pub async fn find_article(state: &AppState, slug: &str) -> ApiResult<(i64, i64)> {
    sqlx::query_as("SELECT id, author_id FROM articles WHERE slug = ?")
        .bind(slug)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

fn article_response(row: ArticleRow) -> Json<Value> {
    Json(json!({ "article": row.into_view(true) }))
}

fn slugify(title: &str) -> String {
    let mut slug = String::new();
    for c in title.chars().flat_map(char::to_lowercase) {
        if c.is_alphanumeric() {
            slug.push(c);
        } else if !slug.is_empty() && !slug.ends_with('-') {
            slug.push('-');
        }
    }
    let slug = slug.trim_end_matches('-').to_string();
    if slug.is_empty() { "article".to_string() } else { slug }
}

/// Picks a slug derived from the title that no other article uses.
async fn unique_slug(tx: &mut Transaction<'_, Sqlite>, title: &str, except_id: i64) -> ApiResult<String> {
    let base = slugify(title);
    let mut candidate = base.clone();
    loop {
        let taken: Option<i64> = sqlx::query_scalar("SELECT id FROM articles WHERE slug = ? AND id != ?")
            .bind(&candidate)
            .bind(except_id)
            .fetch_optional(&mut **tx)
            .await?;
        if taken.is_none() {
            return Ok(candidate);
        }
        let suffix = uuid::Uuid::new_v4().simple().to_string();
        candidate = format!("{base}-{}", &suffix[..8]);
    }
}

/// Trims tags, drops empty ones and duplicates, keeping the first-seen order.
fn clean_tags(tags: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for tag in tags {
        let tag = tag.trim().to_string();
        if !tag.is_empty() && !out.contains(&tag) {
            out.push(tag);
        }
    }
    out
}

async fn set_tags(tx: &mut Transaction<'_, Sqlite>, article_id: i64, tags: &[String]) -> ApiResult<()> {
    sqlx::query("DELETE FROM article_tags WHERE article_id = ?").bind(article_id).execute(&mut **tx).await?;
    for (position, tag) in tags.iter().enumerate() {
        sqlx::query("INSERT INTO tags (name) VALUES (?) ON CONFLICT(name) DO NOTHING")
            .bind(tag)
            .execute(&mut **tx)
            .await?;
        sqlx::query(
            "INSERT INTO article_tags (article_id, tag_id, position) SELECT ?, id, ? FROM tags WHERE name = ?",
        )
        .bind(article_id)
        .bind(position as i64)
        .bind(tag)
        .execute(&mut **tx)
        .await?;
    }
    Ok(())
}

#[derive(Deserialize)]
pub struct ListQuery {
    tag: Option<String>,
    author: Option<String>,
    favorited: Option<String>,
    limit: Option<i64>,
    offset: Option<i64>,
}

async fn list(state: &AppState, viewer_id: i64, q: ListQuery, feed: bool) -> ApiResult<Json<Value>> {
    let limit = q.limit.unwrap_or(20).clamp(0, 1000);
    let offset = q.offset.unwrap_or(0).max(0);

    let rows: Vec<ArticleRow> =
        sqlx::query_as(&format!("{ARTICLE_SELECT} {LIST_FILTER} ORDER BY a.created_at DESC, a.id DESC LIMIT ?6 OFFSET ?7"))
            .bind(viewer_id)
            .bind(&q.tag)
            .bind(&q.author)
            .bind(&q.favorited)
            .bind(feed)
            .bind(limit)
            .bind(offset)
            .fetch_all(&state.pool)
            .await?;

    let count: i64 =
        sqlx::query_scalar(&format!("SELECT COUNT(*) FROM articles a JOIN users u ON u.id = a.author_id {LIST_FILTER}"))
            .bind(viewer_id)
            .bind(&q.tag)
            .bind(&q.author)
            .bind(&q.favorited)
            .bind(feed)
            .fetch_one(&state.pool)
            .await?;

    let articles: Vec<ArticleView> = rows.into_iter().map(|r| r.into_view(false)).collect();
    Ok(Json(json!({ "articles": articles, "articlesCount": count })))
}

pub async fn list_articles(
    State(state): State<AppState>,
    viewer: MaybeAuthUser,
    Query(q): Query<ListQuery>,
) -> ApiResult<Json<Value>> {
    list(&state, viewer.viewer_id(), q, false).await
}

pub async fn feed(State(state): State<AppState>, user: AuthUser, Query(q): Query<ListQuery>) -> ApiResult<Json<Value>> {
    let q = ListQuery { tag: None, author: None, favorited: None, ..q };
    list(&state, user.id, q, true).await
}

pub async fn get_article(
    State(state): State<AppState>,
    viewer: MaybeAuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    Ok(article_response(load_by_slug(&state, &slug, viewer.viewer_id()).await?))
}

#[derive(Deserialize)]
pub struct CreateRequest {
    article: NewArticle,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NewArticle {
    title: Option<String>,
    description: Option<String>,
    body: Option<String>,
    tag_list: Option<Vec<String>>,
}

fn required(value: Option<String>, field: &str) -> ApiResult<String> {
    match value {
        Some(v) if !v.trim().is_empty() => Ok(v),
        _ => Err(ApiError::blank(field)),
    }
}

pub async fn create_article(
    State(state): State<AppState>,
    user: AuthUser,
    ApiJson(req): ApiJson<CreateRequest>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let a = req.article;
    let title = required(a.title, "title")?;
    let description = required(a.description, "description")?;
    let body = required(a.body, "body")?;
    let tags = clean_tags(a.tag_list.unwrap_or_default());
    let now = db::now();

    let mut tx = state.pool.begin().await?;
    let slug = unique_slug(&mut tx, &title, 0).await?;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(&slug)
    .bind(&title)
    .bind(&description)
    .bind(&body)
    .bind(user.id)
    .bind(&now)
    .bind(&now)
    .fetch_one(&mut *tx)
    .await?;
    set_tags(&mut tx, id, &tags).await?;
    tx.commit().await?;

    Ok((StatusCode::CREATED, article_response(load_by_slug(&state, &slug, user.id).await?)))
}

#[derive(Deserialize)]
pub struct UpdateRequest {
    article: ArticleChanges,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ArticleChanges {
    #[serde(default, deserialize_with = "double_option")]
    title: Option<Option<String>>,
    #[serde(default, deserialize_with = "double_option")]
    description: Option<Option<String>>,
    #[serde(default, deserialize_with = "double_option")]
    body: Option<Option<String>>,
    #[serde(default, deserialize_with = "double_option")]
    tag_list: Option<Option<Vec<String>>>,
}

pub async fn update_article(
    State(state): State<AppState>,
    user: AuthUser,
    Path(slug): Path<String>,
    ApiJson(req): ApiJson<UpdateRequest>,
) -> ApiResult<Json<Value>> {
    let current = load_by_slug(&state, &slug, user.id).await?;
    if current.author_id != user.id {
        return Err(ApiError::forbidden("article"));
    }
    let changes = req.article;
    let title = changes.title.map(|v| required(v, "title")).transpose()?;
    let description = changes.description.map(|v| required(v, "description")).transpose()?;
    let body = changes.body.map(|v| required(v, "body")).transpose()?;
    let tags = match changes.tag_list {
        Some(Some(tags)) => Some(clean_tags(tags)),
        Some(None) => return Err(ApiError::validation("tagList", "can't be null")),
        None => None,
    };

    let mut tx = state.pool.begin().await?;
    let new_slug = match &title {
        Some(t) if *t != current.title => unique_slug(&mut tx, t, current.id).await?,
        _ => current.slug.clone(),
    };
    // Guarantee updatedAt moves forward even when two writes land in the same millisecond.
    let mut now = db::now();
    if now <= current.updated_at {
        now = bump_millis(&current.updated_at);
    }
    sqlx::query("UPDATE articles SET slug = ?, title = ?, description = ?, body = ?, updated_at = ? WHERE id = ?")
        .bind(&new_slug)
        .bind(title.unwrap_or(current.title))
        .bind(description.unwrap_or(current.description))
        .bind(body.unwrap_or(current.body))
        .bind(&now)
        .bind(current.id)
        .execute(&mut *tx)
        .await?;
    if let Some(tags) = tags {
        set_tags(&mut tx, current.id, &tags).await?;
    }
    tx.commit().await?;

    Ok(article_response(load_by_slug(&state, &new_slug, user.id).await?))
}

fn bump_millis(timestamp: &str) -> String {
    chrono::DateTime::parse_from_rfc3339(timestamp)
        .map(|t| (t + chrono::Duration::milliseconds(1)).format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
        .unwrap_or_else(|_| db::now())
}

pub async fn delete_article(
    State(state): State<AppState>,
    user: AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<StatusCode> {
    let (id, author_id) = find_article(&state, &slug).await?;
    if author_id != user.id {
        return Err(ApiError::forbidden("article"));
    }
    sqlx::query("DELETE FROM articles WHERE id = ?").bind(id).execute(&state.pool).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn favorite(
    State(state): State<AppState>,
    user: AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let (id, _) = find_article(&state, &slug).await?;
    sqlx::query("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)")
        .bind(user.id)
        .bind(id)
        .execute(&state.pool)
        .await?;
    Ok(article_response(load_by_slug(&state, &slug, user.id).await?))
}

pub async fn unfavorite(
    State(state): State<AppState>,
    user: AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let (id, _) = find_article(&state, &slug).await?;
    sqlx::query("DELETE FROM favorites WHERE user_id = ? AND article_id = ?")
        .bind(user.id)
        .bind(id)
        .execute(&state.pool)
        .await?;
    Ok(article_response(load_by_slug(&state, &slug, user.id).await?))
}

pub async fn list_tags(State(state): State<AppState>) -> ApiResult<Json<Value>> {
    let tags: Vec<String> = sqlx::query_scalar(
        "SELECT t.name FROM tags t JOIN article_tags at ON at.tag_id = t.id
         GROUP BY t.id ORDER BY COUNT(*) DESC, t.name",
    )
    .fetch_all(&state.pool)
    .await?;
    Ok(Json(json!({ "tags": tags })))
}
