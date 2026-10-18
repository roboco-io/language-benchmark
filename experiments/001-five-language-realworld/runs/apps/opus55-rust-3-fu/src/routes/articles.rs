use axum::Json;
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use rand::Rng;
use rand::distributions::Alphanumeric;
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::{Sqlite, SqliteConnection, Transaction};

use crate::AppState;
use crate::auth::{AuthUser, MaybeUser};
use crate::db::now;
use crate::error::{ApiError, ApiResult, Validation};
use crate::extract::{JsonBody, present};

const DEFAULT_LIMIT: i64 = 20;
const MAX_LIMIT: i64 = 1000;

/// Shared SELECT for rendering articles from the point of view of `?1`
/// (the viewer's user id, or NULL when anonymous).
const ARTICLE_SELECT: &str = "SELECT a.id, a.slug, a.title, a.description, a.body, \
    a.created_at, a.updated_at, a.author_id, \
    u.username AS author_username, u.bio AS author_bio, u.image AS author_image, \
    EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ?1 AND f.followee_id = a.author_id) AS following, \
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.user_id = ?1 AND fv.article_id = a.id) AS favorited, \
    (SELECT COUNT(*) FROM favorites fv WHERE fv.article_id = a.id) AS favorites_count, \
    (SELECT json_group_array(name) FROM ( \
        SELECT t.name AS name FROM article_tags at JOIN tags t ON t.id = at.tag_id \
        WHERE at.article_id = a.id ORDER BY at.position)) AS tag_list \
    FROM articles a JOIN users u ON u.id = a.author_id";

#[derive(sqlx::FromRow)]
pub struct ArticleRow {
    slug: String,
    title: String,
    description: String,
    body: String,
    created_at: String,
    updated_at: String,
    author_username: String,
    author_bio: Option<String>,
    author_image: Option<String>,
    following: bool,
    favorited: bool,
    favorites_count: i64,
    tag_list: String,
}

impl ArticleRow {
    fn to_json(&self, with_body: bool) -> Value {
        let tags: Vec<String> = serde_json::from_str(&self.tag_list).unwrap_or_default();
        let mut v = json!({
            "slug": self.slug,
            "title": self.title,
            "description": self.description,
            "tagList": tags,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "favorited": self.favorited,
            "favoritesCount": self.favorites_count,
            "author": {
                "username": self.author_username,
                "bio": self.author_bio,
                "image": self.author_image,
                "following": self.following,
            }
        });
        if with_body {
            v["body"] = json!(self.body);
        }
        v
    }
}

fn single(row: &ArticleRow) -> Json<Value> {
    Json(json!({ "article": row.to_json(true) }))
}

async fn load_by_slug(state: &AppState, slug: &str, viewer: Option<i64>) -> ApiResult<ArticleRow> {
    let sql = format!("{ARTICLE_SELECT} WHERE a.slug = ?2");
    sqlx::query_as::<_, ArticleRow>(&sql)
        .bind(viewer)
        .bind(slug)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

/// Looks up `(id, author_id)` for a slug, or 404.
pub async fn find_article(state: &AppState, slug: &str) -> ApiResult<(i64, i64)> {
    sqlx::query_as::<_, (i64, i64)>("SELECT id, author_id FROM articles WHERE slug = ?")
        .bind(slug)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

// ---------------------------------------------------------------------------
// Listing
// ---------------------------------------------------------------------------

/// Query parameters are taken as strings so malformed numbers fall back to
/// defaults instead of producing a 400.
#[derive(Deserialize)]
pub struct ListQuery {
    tag: Option<String>,
    author: Option<String>,
    favorited: Option<String>,
    limit: Option<String>,
    offset: Option<String>,
}

fn paging(q: &ListQuery) -> (i64, i64) {
    let limit = q
        .limit
        .as_deref()
        .and_then(|s| s.parse::<i64>().ok())
        .filter(|l| *l >= 0)
        .unwrap_or(DEFAULT_LIMIT)
        .min(MAX_LIMIT);
    let offset = q
        .offset
        .as_deref()
        .and_then(|s| s.parse::<i64>().ok())
        .filter(|o| *o >= 0)
        .unwrap_or(0);
    (limit, offset)
}

async fn list_where(
    state: &AppState,
    viewer: Option<i64>,
    filter: &str,
    q: &ListQuery,
) -> ApiResult<Json<Value>> {
    let (limit, offset) = paging(q);
    let sql = format!(
        "{ARTICLE_SELECT} WHERE {filter} ORDER BY a.created_at DESC, a.id DESC LIMIT ?5 OFFSET ?6"
    );
    let rows = sqlx::query_as::<_, ArticleRow>(&sql)
        .bind(viewer)
        .bind(&q.tag)
        .bind(&q.author)
        .bind(&q.favorited)
        .bind(limit)
        .bind(offset)
        .fetch_all(&state.pool)
        .await?;

    let count_sql = format!(
        "SELECT COUNT(*) FROM articles a JOIN users u ON u.id = a.author_id \
         WHERE ?1 IS ?1 AND {filter}"
    );
    let count: i64 = sqlx::query_scalar(&count_sql)
        .bind(viewer)
        .bind(&q.tag)
        .bind(&q.author)
        .bind(&q.favorited)
        .fetch_one(&state.pool)
        .await?;

    let articles: Vec<Value> = rows.iter().map(|r| r.to_json(false)).collect();
    Ok(Json(
        json!({ "articles": articles, "articlesCount": count }),
    ))
}

/// Filter on `?2` tag, `?3` author username and `?4` favoriting username.
const LIST_FILTER: &str = "(?2 IS NULL OR EXISTS(SELECT 1 FROM article_tags at JOIN tags t ON t.id = at.tag_id \
        WHERE at.article_id = a.id AND t.name = ?2)) \
    AND (?3 IS NULL OR u.username = ?3) \
    AND (?4 IS NULL OR EXISTS(SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id \
        WHERE fv.article_id = a.id AND fu.username = ?4))";

/// Articles authored by users the viewer `?1` follows. The unused `?2`..`?4`
/// are referenced so the bind positions match the generic list query.
const FEED_FILTER: &str = "a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?1) \
    AND (?2 IS ?2) AND (?3 IS ?3) AND (?4 IS ?4)";

pub async fn list(
    State(state): State<AppState>,
    MaybeUser(viewer): MaybeUser,
    Query(q): Query<ListQuery>,
) -> ApiResult<Json<Value>> {
    list_where(&state, viewer, LIST_FILTER, &q).await
}

pub async fn feed(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Query(mut q): Query<ListQuery>,
) -> ApiResult<Json<Value>> {
    q.tag = None;
    q.author = None;
    q.favorited = None;
    list_where(&state, Some(me), FEED_FILTER, &q).await
}

// ---------------------------------------------------------------------------
// CRUD
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct Envelope<T> {
    article: T,
}

#[derive(Deserialize)]
pub struct NewArticle {
    title: Option<String>,
    description: Option<String>,
    body: Option<String>,
    #[serde(rename = "tagList")]
    tag_list: Option<Vec<String>>,
}

#[derive(Deserialize)]
pub struct UpdateArticle {
    #[serde(default, deserialize_with = "present")]
    title: Option<Option<String>>,
    #[serde(default, deserialize_with = "present")]
    description: Option<Option<String>>,
    #[serde(default, deserialize_with = "present")]
    body: Option<Option<String>>,
    #[serde(default, rename = "tagList", deserialize_with = "present")]
    tag_list: Option<Option<Vec<String>>>,
}

fn blank(s: &Option<String>) -> bool {
    s.as_deref().map(str::trim).unwrap_or("").is_empty()
}

fn slugify(title: &str) -> String {
    let mut slug = String::with_capacity(title.len());
    let mut dash = false;
    for c in title.chars().flat_map(char::to_lowercase) {
        if c.is_alphanumeric() {
            slug.push(c);
            dash = false;
        } else if !dash && !slug.is_empty() {
            slug.push('-');
            dash = true;
        }
    }
    while slug.ends_with('-') {
        slug.pop();
    }
    if slug.is_empty() {
        slug.push_str("article");
    }
    slug
}

/// Builds a slug from the title that is not used by any other article.
async fn unique_slug(
    conn: &mut SqliteConnection,
    title: &str,
    except_id: i64,
) -> ApiResult<String> {
    let base = slugify(title);
    let mut candidate = base.clone();
    loop {
        let exists: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM articles WHERE slug = ? AND id != ?)")
                .bind(&candidate)
                .bind(except_id)
                .fetch_one(&mut *conn)
                .await?;
        if !exists {
            return Ok(candidate);
        }
        let suffix: String = rand::thread_rng()
            .sample_iter(&Alphanumeric)
            .take(8)
            .map(|c| (c as char).to_ascii_lowercase())
            .collect();
        candidate = format!("{base}-{suffix}");
    }
}

/// Replaces the article's tags, preserving the given order and dropping
/// blanks and duplicates.
async fn set_tags(
    tx: &mut Transaction<'_, Sqlite>,
    article_id: i64,
    tags: &[String],
) -> ApiResult<()> {
    sqlx::query("DELETE FROM article_tags WHERE article_id = ?")
        .bind(article_id)
        .execute(&mut **tx)
        .await?;
    let mut seen: Vec<&str> = Vec::new();
    for tag in tags.iter().map(|t| t.trim()).filter(|t| !t.is_empty()) {
        if seen.contains(&tag) {
            continue;
        }
        seen.push(tag);
        sqlx::query("INSERT OR IGNORE INTO tags (name) VALUES (?)")
            .bind(tag)
            .execute(&mut **tx)
            .await?;
        sqlx::query(
            "INSERT INTO article_tags (article_id, tag_id, position) \
             SELECT ?, id, ? FROM tags WHERE name = ?",
        )
        .bind(article_id)
        .bind(seen.len() as i64)
        .bind(tag)
        .execute(&mut **tx)
        .await?;
    }
    Ok(())
}

pub async fn create(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    JsonBody(body): JsonBody<Envelope<NewArticle>>,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let a = body.article;
    let mut v = Validation::default();
    if blank(&a.title) {
        v.add("title", "can't be blank");
    }
    if blank(&a.description) {
        v.add("description", "can't be blank");
    }
    if blank(&a.body) {
        v.add("body", "can't be blank");
    }
    v.finish()?;

    let title = a.title.unwrap();
    let ts = now();
    let mut tx = state.pool.begin().await?;
    let slug = unique_slug(&mut tx, &title, 0).await?;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(&slug)
    .bind(&title)
    .bind(a.description.unwrap())
    .bind(a.body.unwrap())
    .bind(me)
    .bind(&ts)
    .bind(&ts)
    .fetch_one(&mut *tx)
    .await?;
    set_tags(&mut tx, id, a.tag_list.as_deref().unwrap_or_default()).await?;
    tx.commit().await?;

    let row = load_by_slug(&state, &slug, Some(me)).await?;
    Ok((StatusCode::CREATED, single(&row)))
}

pub async fn get_one(
    State(state): State<AppState>,
    MaybeUser(viewer): MaybeUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    Ok(single(&load_by_slug(&state, &slug, viewer).await?))
}

pub async fn update(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path(slug): Path<String>,
    JsonBody(body): JsonBody<Envelope<UpdateArticle>>,
) -> ApiResult<Json<Value>> {
    let (id, author_id) = find_article(&state, &slug).await?;
    if author_id != me {
        return Err(ApiError::forbidden("article"));
    }

    let changes = body.article;
    let mut v = Validation::default();
    for (field, value) in [
        ("title", &changes.title),
        ("description", &changes.description),
        ("body", &changes.body),
    ] {
        if value.as_ref().is_some_and(blank) {
            v.add(field, "can't be blank");
        }
    }
    if matches!(changes.tag_list, Some(None)) {
        v.add("tagList", "must be an array");
    }
    v.finish()?;

    let mut tx = state.pool.begin().await?;
    let mut new_slug = slug.clone();
    if let Some(Some(title)) = &changes.title {
        new_slug = unique_slug(&mut tx, title, id).await?;
        sqlx::query("UPDATE articles SET title = ?, slug = ? WHERE id = ?")
            .bind(title)
            .bind(&new_slug)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(Some(description)) = &changes.description {
        sqlx::query("UPDATE articles SET description = ? WHERE id = ?")
            .bind(description)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(Some(text)) = &changes.body {
        sqlx::query("UPDATE articles SET body = ? WHERE id = ?")
            .bind(text)
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    if let Some(Some(tags)) = &changes.tag_list {
        set_tags(&mut tx, id, tags).await?;
    }
    sqlx::query("UPDATE articles SET updated_at = ? WHERE id = ?")
        .bind(now())
        .bind(id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    Ok(single(&load_by_slug(&state, &new_slug, Some(me)).await?))
}

pub async fn remove(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<StatusCode> {
    let (id, author_id) = find_article(&state, &slug).await?;
    if author_id != me {
        return Err(ApiError::forbidden("article"));
    }
    sqlx::query("DELETE FROM articles WHERE id = ?")
        .bind(id)
        .execute(&state.pool)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------------------------------------------------------------------------
// Favorites
// ---------------------------------------------------------------------------

pub async fn favorite(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let (id, _) = find_article(&state, &slug).await?;
    sqlx::query("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)")
        .bind(me)
        .bind(id)
        .execute(&state.pool)
        .await?;
    Ok(single(&load_by_slug(&state, &slug, Some(me)).await?))
}

pub async fn unfavorite(
    State(state): State<AppState>,
    AuthUser(me): AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let (id, _) = find_article(&state, &slug).await?;
    sqlx::query("DELETE FROM favorites WHERE user_id = ? AND article_id = ?")
        .bind(me)
        .bind(id)
        .execute(&state.pool)
        .await?;
    Ok(single(&load_by_slug(&state, &slug, Some(me)).await?))
}
