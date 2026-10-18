use std::collections::HashMap;

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use serde::Deserialize;
use serde_json::{json, Map, Value};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use crate::{
    auth::{AuthUser, MaybeUser},
    body::{field, optional_nonblank_str, required_str, Field, JsonBody},
    error::{AppError, AppResult},
    now_timestamp, AppState,
};

const DEFAULT_LIMIT: i64 = 20;
const MAX_LIMIT: i64 = 100;

#[derive(sqlx::FromRow)]
pub struct ArticleRow {
    pub id: i64,
    pub author_id: i64,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub body: String,
    pub created_at: String,
    pub updated_at: String,
    pub username: String,
    pub bio: Option<String>,
    pub image: Option<String>,
    pub favorites_count: i64,
    pub favorited: bool,
    pub following: bool,
}

impl ArticleRow {
    fn to_json(&self, tags: &[String], include_body: bool) -> Value {
        let mut article = json!({
            "slug": self.slug,
            "title": self.title,
            "description": self.description,
            "tagList": tags,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "favorited": self.favorited,
            "favoritesCount": self.favorites_count,
            "author": {
                "username": self.username,
                "bio": self.bio,
                "image": self.image,
                "following": self.following,
            },
        });
        if include_body {
            article["body"] = json!(self.body);
        }
        article
    }
}

/// Starts a SELECT that yields `ArticleRow`s as seen by `viewer`.
fn select_articles(viewer: Option<i64>) -> QueryBuilder<'static, Sqlite> {
    let mut query = QueryBuilder::new(
        "SELECT a.id, a.author_id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at, \
         u.username, u.bio, u.image, \
         (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS favorites_count, \
         EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ",
    );
    query.push_bind(viewer);
    query.push(
        ") AS favorited, \
         EXISTS(SELECT 1 FROM follows fo WHERE fo.followee_id = a.author_id AND fo.follower_id = ",
    );
    query.push_bind(viewer);
    query.push(") AS following FROM articles a JOIN users u ON u.id = a.author_id");
    query
}

async fn tags_for(pool: &sqlx::SqlitePool, ids: &[i64]) -> AppResult<HashMap<i64, Vec<String>>> {
    let mut map: HashMap<i64, Vec<String>> = HashMap::new();
    if ids.is_empty() {
        return Ok(map);
    }
    let mut query = QueryBuilder::<Sqlite>::new(
        "SELECT at.article_id, t.name FROM article_tags at JOIN tags t ON t.id = at.tag_id \
         WHERE at.article_id IN (",
    );
    let mut separated = query.separated(", ");
    for id in ids {
        separated.push_bind(*id);
    }
    query.push(") ORDER BY at.article_id, at.position");
    let rows: Vec<(i64, String)> = query.build_query_as().fetch_all(pool).await?;
    for (id, name) in rows {
        map.entry(id).or_default().push(name);
    }
    Ok(map)
}

async fn load_article(state: &AppState, slug: &str, viewer: Option<i64>) -> AppResult<ArticleRow> {
    let mut query = select_articles(viewer);
    query.push(" WHERE a.slug = ").push_bind(slug.to_string());
    query
        .build_query_as()
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::not_found("article"))
}

/// Builds the `{"article": ...}` response for a single article.
async fn article_response(state: &AppState, slug: &str, viewer: Option<i64>) -> AppResult<Json<Value>> {
    let article = load_article(state, slug, viewer).await?;
    let tags = tags_for(&state.pool, &[article.id]).await?;
    let tags = tags.get(&article.id).map(Vec::as_slice).unwrap_or(&[]);
    Ok(Json(json!({ "article": article.to_json(tags, true) })))
}

#[derive(Deserialize)]
pub struct ListParams {
    tag: Option<String>,
    author: Option<String>,
    favorited: Option<String>,
    limit: Option<String>,
    offset: Option<String>,
}

enum Filter {
    Tag(String),
    Author(String),
    FavoritedBy(String),
    FeedOf(i64),
}

fn push_filters(query: &mut QueryBuilder<'static, Sqlite>, filters: &[Filter]) {
    for (i, filter) in filters.iter().enumerate() {
        query.push(if i == 0 { " WHERE " } else { " AND " });
        match filter {
            Filter::Tag(tag) => {
                query.push(
                    "a.id IN (SELECT at.article_id FROM article_tags at \
                     JOIN tags t ON t.id = at.tag_id WHERE t.name = ",
                );
                query.push_bind(tag.clone()).push(")");
            }
            Filter::Author(author) => {
                query.push("u.username = ").push_bind(author.clone());
            }
            Filter::FavoritedBy(username) => {
                query.push(
                    "a.id IN (SELECT f.article_id FROM favorites f \
                     JOIN users fu ON fu.id = f.user_id WHERE fu.username = ",
                );
                query.push_bind(username.clone()).push(")");
            }
            Filter::FeedOf(user_id) => {
                query.push("a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ");
                query.push_bind(*user_id).push(")");
            }
        }
    }
}

fn parse_page(params: &ListParams) -> (i64, i64) {
    let parse = |value: &Option<String>| value.as_deref().and_then(|v| v.parse::<i64>().ok());
    let limit = parse(&params.limit)
        .filter(|l| *l >= 0)
        .unwrap_or(DEFAULT_LIMIT)
        .min(MAX_LIMIT);
    let offset = parse(&params.offset).filter(|o| *o >= 0).unwrap_or(0);
    (limit, offset)
}

async fn list_response(
    state: &AppState,
    viewer: Option<i64>,
    filters: Vec<Filter>,
    params: &ListParams,
) -> AppResult<Json<Value>> {
    let (limit, offset) = parse_page(params);

    let mut count_query =
        QueryBuilder::new("SELECT COUNT(*) FROM articles a JOIN users u ON u.id = a.author_id");
    push_filters(&mut count_query, &filters);
    let count: i64 = count_query.build_query_scalar().fetch_one(&state.pool).await?;

    let mut query = select_articles(viewer);
    push_filters(&mut query, &filters);
    query.push(" ORDER BY a.created_at DESC, a.id DESC LIMIT ");
    query.push_bind(limit).push(" OFFSET ").push_bind(offset);
    let rows: Vec<ArticleRow> = query.build_query_as().fetch_all(&state.pool).await?;

    let ids: Vec<i64> = rows.iter().map(|row| row.id).collect();
    let tags = tags_for(&state.pool, &ids).await?;
    let articles: Vec<Value> = rows
        .iter()
        .map(|row| {
            let row_tags = tags.get(&row.id).map(Vec::as_slice).unwrap_or(&[]);
            row.to_json(row_tags, false)
        })
        .collect();

    Ok(Json(json!({ "articles": articles, "articlesCount": count })))
}

pub async fn list_articles(
    State(state): State<AppState>,
    viewer: MaybeUser,
    Query(params): Query<ListParams>,
) -> AppResult<Json<Value>> {
    let mut filters = Vec::new();
    if let Some(tag) = params.tag.clone() {
        filters.push(Filter::Tag(tag));
    }
    if let Some(author) = params.author.clone() {
        filters.push(Filter::Author(author));
    }
    if let Some(username) = params.favorited.clone() {
        filters.push(Filter::FavoritedBy(username));
    }
    list_response(&state, viewer.id(), filters, &params).await
}

pub async fn feed(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(params): Query<ListParams>,
) -> AppResult<Json<Value>> {
    list_response(&state, Some(auth.id), vec![Filter::FeedOf(auth.id)], &params).await
}

pub async fn get_article(
    State(state): State<AppState>,
    viewer: MaybeUser,
    Path(slug): Path<String>,
) -> AppResult<Json<Value>> {
    article_response(&state, &slug, viewer.id()).await
}

/// Lowercases the title and joins its alphanumeric runs with dashes.
fn slugify(title: &str) -> String {
    let slug = title
        .to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    if slug.is_empty() {
        "article".to_string()
    } else {
        slug
    }
}

/// Returns a slug derived from `title` that no other article uses.
async fn unique_slug(conn: &mut SqliteConnection, title: &str, except_id: i64) -> AppResult<String> {
    let base = slugify(title);
    let mut candidate = base.clone();
    let mut suffix = 1;
    loop {
        let taken: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM articles WHERE slug = ? AND id != ?)")
                .bind(&candidate)
                .bind(except_id)
                .fetch_one(&mut *conn)
                .await?;
        if !taken {
            return Ok(candidate);
        }
        suffix += 1;
        candidate = format!("{base}-{suffix}");
    }
}

/// Parses `tagList`: absent → `None`, array of strings → deduplicated tags, anything else → 422.
fn parse_tags(input: &Map<String, Value>) -> AppResult<Option<Vec<String>>> {
    let invalid = || AppError::invalid("tagList", "must be an array of strings");
    match field(input, "tagList") {
        Field::Absent => Ok(None),
        Field::Other(Value::Array(items)) => {
            let mut tags: Vec<String> = Vec::new();
            for item in items {
                let tag = item.as_str().ok_or_else(invalid)?.trim().to_string();
                if !tag.is_empty() && !tags.contains(&tag) {
                    tags.push(tag);
                }
            }
            Ok(Some(tags))
        }
        _ => Err(invalid()),
    }
}

async fn replace_tags(conn: &mut SqliteConnection, article_id: i64, tags: &[String]) -> AppResult<()> {
    sqlx::query("DELETE FROM article_tags WHERE article_id = ?")
        .bind(article_id)
        .execute(&mut *conn)
        .await?;
    for (position, tag) in tags.iter().enumerate() {
        let tag_id: i64 = sqlx::query_scalar(
            "INSERT INTO tags (name) VALUES (?) ON CONFLICT(name) DO UPDATE SET name = excluded.name RETURNING id",
        )
        .bind(tag)
        .fetch_one(&mut *conn)
        .await?;
        sqlx::query("INSERT INTO article_tags (article_id, tag_id, position) VALUES (?, ?, ?)")
            .bind(article_id)
            .bind(tag_id)
            .bind(position as i64)
            .execute(&mut *conn)
            .await?;
    }
    Ok(())
}

pub async fn create_article(
    State(state): State<AppState>,
    auth: AuthUser,
    body: JsonBody,
) -> AppResult<(StatusCode, Json<Value>)> {
    let input = body.object("article");
    let title = required_str(&input, "title")?;
    let description = required_str(&input, "description")?;
    let article_body = required_str(&input, "body")?;
    let tags = parse_tags(&input)?.unwrap_or_default();

    let mut tx = state.pool.begin().await?;
    let slug = unique_slug(&mut tx, &title, 0).await?;
    let now = now_timestamp();
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
    )
    .bind(&slug)
    .bind(&title)
    .bind(&description)
    .bind(&article_body)
    .bind(auth.id)
    .bind(&now)
    .bind(&now)
    .fetch_one(&mut *tx)
    .await?;
    replace_tags(&mut tx, id, &tags).await?;
    tx.commit().await?;

    let response = article_response(&state, &slug, Some(auth.id)).await?;
    Ok((StatusCode::CREATED, response))
}

/// Loads the article and checks that `user_id` is its author.
async fn load_owned_article(state: &AppState, slug: &str, user_id: i64) -> AppResult<ArticleRow> {
    let article = load_article(state, slug, Some(user_id)).await?;
    if article.author_id != user_id {
        return Err(AppError::forbidden("article"));
    }
    Ok(article)
}

pub async fn update_article(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
    body: JsonBody,
) -> AppResult<Json<Value>> {
    let article = load_owned_article(&state, &slug, auth.id).await?;
    let input = body.object("article");
    let title = optional_nonblank_str(&input, "title")?;
    let description = optional_nonblank_str(&input, "description")?;
    let article_body = optional_nonblank_str(&input, "body")?;
    let tags = parse_tags(&input)?;

    let mut tx = state.pool.begin().await?;
    let new_slug = match &title {
        Some(title) if *title != article.title => unique_slug(&mut tx, title, article.id).await?,
        _ => article.slug.clone(),
    };
    // Guarantee updatedAt moves forward even for updates within the same millisecond.
    let mut now = now_timestamp();
    if now <= article.updated_at {
        now = crate::bump_timestamp(&article.updated_at);
    }
    sqlx::query(
        "UPDATE articles SET slug = ?, title = ?, description = ?, body = ?, updated_at = ? WHERE id = ?",
    )
    .bind(&new_slug)
    .bind(title.as_ref().unwrap_or(&article.title))
    .bind(description.as_ref().unwrap_or(&article.description))
    .bind(article_body.as_ref().unwrap_or(&article.body))
    .bind(&now)
    .bind(article.id)
    .execute(&mut *tx)
    .await?;
    if let Some(tags) = tags {
        replace_tags(&mut tx, article.id, &tags).await?;
    }
    tx.commit().await?;

    article_response(&state, &new_slug, Some(auth.id)).await
}

pub async fn delete_article(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
) -> AppResult<StatusCode> {
    let article = load_owned_article(&state, &slug, auth.id).await?;
    sqlx::query("DELETE FROM articles WHERE id = ?")
        .bind(article.id)
        .execute(&state.pool)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn favorite(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
) -> AppResult<Json<Value>> {
    let article = load_article(&state, &slug, Some(auth.id)).await?;
    sqlx::query("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)")
        .bind(auth.id)
        .bind(article.id)
        .execute(&state.pool)
        .await?;
    article_response(&state, &slug, Some(auth.id)).await
}

pub async fn unfavorite(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
) -> AppResult<Json<Value>> {
    let article = load_article(&state, &slug, Some(auth.id)).await?;
    sqlx::query("DELETE FROM favorites WHERE user_id = ? AND article_id = ?")
        .bind(auth.id)
        .bind(article.id)
        .execute(&state.pool)
        .await?;
    article_response(&state, &slug, Some(auth.id)).await
}

/// Returns an article's id, or 404 when the slug is unknown.
pub async fn article_id(state: &AppState, slug: &str) -> AppResult<i64> {
    sqlx::query_scalar("SELECT id FROM articles WHERE slug = ?")
        .bind(slug)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::not_found("article"))
}

pub async fn list_tags(State(state): State<AppState>) -> AppResult<Json<Value>> {
    let tags: Vec<String> = sqlx::query_scalar(
        "SELECT t.name FROM tags t JOIN article_tags at ON at.tag_id = t.id \
         GROUP BY t.id ORDER BY COUNT(*) DESC, t.name",
    )
    .fetch_all(&state.pool)
    .await?;
    Ok(Json(json!({ "tags": tags })))
}
