use crate::{
    auth::{hash_password, make_token, verify_password, AuthUser, MaybeUser},
    error::{ApiError, ApiResult},
    AppState,
};
use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use rand::Rng;
use serde::Deserialize;
use serde_json::{json, Map, Value};
use sqlx::{sqlite::SqliteRow, QueryBuilder, Row, Sqlite};

fn now() -> String {
    chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

/// Parses the request body and returns the object stored under `key`.
fn wrapped(body: &Bytes, key: &str) -> ApiResult<Map<String, Value>> {
    let v: Value = serde_json::from_slice(body).map_err(|_| ApiError::unprocessable("body", "is invalid"))?;
    match v.get(key) {
        Some(Value::Object(m)) => Ok(m.clone()),
        _ => Err(ApiError::blank(key)),
    }
}

/// Required string field: must be present, a string, and not blank.
fn required(m: &Map<String, Value>, field: &str) -> ApiResult<String> {
    match m.get(field) {
        Some(Value::String(s)) if !s.trim().is_empty() => Ok(s.clone()),
        _ => Err(ApiError::blank(field)),
    }
}

/// Optional string field that, when present, must not be null or blank.
fn present_nonblank(m: &Map<String, Value>, field: &str) -> ApiResult<Option<String>> {
    if m.contains_key(field) { required(m, field).map(Some) } else { Ok(None) }
}

/// Nullable field: Some(None) clears the value (null or empty string).
fn nullable(m: &Map<String, Value>, field: &str) -> Option<Option<String>> {
    match m.get(field)? {
        Value::String(s) if !s.is_empty() => Some(Some(s.clone())),
        _ => Some(None),
    }
}

fn check_password(pw: &str) -> ApiResult<()> {
    if pw.chars().count() < 8 {
        return Err(ApiError::unprocessable("password", "is too short (minimum is 8 characters)"));
    }
    Ok(())
}

fn is_unique_violation(e: &sqlx::Error) -> bool {
    matches!(e, sqlx::Error::Database(d) if d.is_unique_violation())
}

// ---------- users ----------

fn user_json(state: &AppState, row: &SqliteRow) -> Value {
    let id: i64 = row.get("id");
    json!({"user": {
        "email": row.get::<String, _>("email"),
        "username": row.get::<String, _>("username"),
        "bio": row.get::<Option<String>, _>("bio"),
        "image": row.get::<Option<String>, _>("image"),
        "token": make_token(&state.jwt_secret, id),
    }})
}

pub async fn register(State(st): State<AppState>, body: Bytes) -> ApiResult<(StatusCode, Json<Value>)> {
    let m = wrapped(&body, "user")?;
    let username = required(&m, "username")?;
    let email = required(&m, "email")?;
    let password = required(&m, "password")?;
    check_password(&password)?;
    if sqlx::query("SELECT 1 FROM users WHERE username = ?").bind(&username).fetch_optional(&st.db).await?.is_some() {
        return Err(ApiError::conflict("username"));
    }
    if sqlx::query("SELECT 1 FROM users WHERE email = ?").bind(&email).fetch_optional(&st.db).await?.is_some() {
        return Err(ApiError::conflict("email"));
    }
    let hash = hash_password(password).await;
    let res = sqlx::query("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)")
        .bind(&username)
        .bind(&email)
        .bind(&hash)
        .execute(&st.db)
        .await
        .map_err(|e| if is_unique_violation(&e) { ApiError::conflict("username") } else { e.into() })?;
    let row = sqlx::query("SELECT * FROM users WHERE id = ?").bind(res.last_insert_rowid()).fetch_one(&st.db).await?;
    Ok((StatusCode::CREATED, Json(user_json(&st, &row))))
}

pub async fn login(State(st): State<AppState>, body: Bytes) -> ApiResult<Json<Value>> {
    let m = wrapped(&body, "user")?;
    let email = required(&m, "email")?;
    let password = required(&m, "password")?;
    let invalid = || ApiError::unauthorized("credentials", "invalid");
    let row = sqlx::query("SELECT * FROM users WHERE email = ?").bind(&email).fetch_optional(&st.db).await?.ok_or_else(invalid)?;
    if !verify_password(password, row.get("password_hash")).await {
        return Err(invalid());
    }
    Ok(Json(user_json(&st, &row)))
}

pub async fn current_user(State(st): State<AppState>, AuthUser(uid): AuthUser) -> ApiResult<Json<Value>> {
    let row = sqlx::query("SELECT * FROM users WHERE id = ?")
        .bind(uid)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;
    Ok(Json(user_json(&st, &row)))
}

pub async fn update_user(State(st): State<AppState>, AuthUser(uid): AuthUser, body: Bytes) -> ApiResult<Json<Value>> {
    let m = wrapped(&body, "user")?;
    let username = present_nonblank(&m, "username")?;
    let email = present_nonblank(&m, "email")?;
    let password = present_nonblank(&m, "password")?;
    if let Some(p) = &password {
        check_password(p)?;
    }
    if let Some(u) = &username {
        if sqlx::query("SELECT 1 FROM users WHERE username = ? AND id != ?").bind(u).bind(uid).fetch_optional(&st.db).await?.is_some() {
            return Err(ApiError::conflict("username"));
        }
    }
    if let Some(e) = &email {
        if sqlx::query("SELECT 1 FROM users WHERE email = ? AND id != ?").bind(e).bind(uid).fetch_optional(&st.db).await?.is_some() {
            return Err(ApiError::conflict("email"));
        }
    }
    let hash = match password {
        Some(p) => Some(hash_password(p).await),
        None => None,
    };
    let bio = nullable(&m, "bio");
    let image = nullable(&m, "image");
    let mut qb: QueryBuilder<Sqlite> = QueryBuilder::new("UPDATE users SET id = id");
    if let Some(v) = &username { qb.push(", username = ").push_bind(v); }
    if let Some(v) = &email { qb.push(", email = ").push_bind(v); }
    if let Some(v) = &hash { qb.push(", password_hash = ").push_bind(v); }
    if let Some(v) = &bio { qb.push(", bio = ").push_bind(v); }
    if let Some(v) = &image { qb.push(", image = ").push_bind(v); }
    qb.push(" WHERE id = ").push_bind(uid);
    qb.build().execute(&st.db).await.map_err(|e| if is_unique_violation(&e) { ApiError::conflict("username") } else { e.into() })?;
    current_user(State(st), AuthUser(uid)).await
}

// ---------- profiles ----------

async fn profile_json(st: &AppState, username: &str, viewer: Option<i64>) -> ApiResult<Value> {
    let row = sqlx::query(
        "SELECT u.username, u.bio, u.image, EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followed_id = u.id) AS following
         FROM users u WHERE u.username = ?",
    )
    .bind(viewer.unwrap_or(-1))
    .bind(username)
    .fetch_optional(&st.db)
    .await?
    .ok_or_else(|| ApiError::not_found("profile"))?;
    Ok(json!({"profile": {
        "username": row.get::<String, _>("username"),
        "bio": row.get::<Option<String>, _>("bio"),
        "image": row.get::<Option<String>, _>("image"),
        "following": row.get::<bool, _>("following"),
    }}))
}

pub async fn get_profile(State(st): State<AppState>, MaybeUser(v): MaybeUser, Path(username): Path<String>) -> ApiResult<Json<Value>> {
    Ok(Json(profile_json(&st, &username, v).await?))
}

async fn user_id_by_name(st: &AppState, username: &str) -> ApiResult<i64> {
    sqlx::query_scalar("SELECT id FROM users WHERE username = ?")
        .bind(username)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("profile"))
}

pub async fn follow(State(st): State<AppState>, AuthUser(uid): AuthUser, Path(username): Path<String>) -> ApiResult<Json<Value>> {
    let target = user_id_by_name(&st, &username).await?;
    sqlx::query("INSERT OR IGNORE INTO follows (follower_id, followed_id) VALUES (?, ?)").bind(uid).bind(target).execute(&st.db).await?;
    Ok(Json(profile_json(&st, &username, Some(uid)).await?))
}

pub async fn unfollow(State(st): State<AppState>, AuthUser(uid): AuthUser, Path(username): Path<String>) -> ApiResult<Json<Value>> {
    let target = user_id_by_name(&st, &username).await?;
    sqlx::query("DELETE FROM follows WHERE follower_id = ? AND followed_id = ?").bind(uid).bind(target).execute(&st.db).await?;
    Ok(Json(profile_json(&st, &username, Some(uid)).await?))
}

// ---------- articles ----------

const ARTICLE_SELECT: &str = "SELECT a.id, a.slug, a.title, a.description, a.body, a.author_id, a.created_at, a.updated_at,
    u.username, u.bio, u.image,
    (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS fav_count,
    EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ?) AS favorited,
    EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followed_id = a.author_id) AS following,
    (SELECT json_group_array(tag) FROM (SELECT tag FROM article_tags t WHERE t.article_id = a.id ORDER BY position)) AS tags
    FROM articles a JOIN users u ON u.id = a.author_id";

fn article_value(row: &SqliteRow, with_body: bool) -> Value {
    let tags: Vec<String> = serde_json::from_str(&row.get::<String, _>("tags")).unwrap_or_default();
    let mut v = json!({
        "slug": row.get::<String, _>("slug"),
        "title": row.get::<String, _>("title"),
        "description": row.get::<String, _>("description"),
        "tagList": tags,
        "createdAt": row.get::<String, _>("created_at"),
        "updatedAt": row.get::<String, _>("updated_at"),
        "favorited": row.get::<bool, _>("favorited"),
        "favoritesCount": row.get::<i64, _>("fav_count"),
        "author": {
            "username": row.get::<String, _>("username"),
            "bio": row.get::<Option<String>, _>("bio"),
            "image": row.get::<Option<String>, _>("image"),
            "following": row.get::<bool, _>("following"),
        },
    });
    if with_body {
        v["body"] = json!(row.get::<String, _>("body"));
    }
    v
}

async fn fetch_article(st: &AppState, slug: &str, viewer: Option<i64>) -> ApiResult<SqliteRow> {
    let v = viewer.unwrap_or(-1);
    sqlx::query(&format!("{ARTICLE_SELECT} WHERE a.slug = ?"))
        .bind(v)
        .bind(v)
        .bind(slug)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

async fn single_article(st: &AppState, slug: &str, viewer: Option<i64>) -> ApiResult<Json<Value>> {
    let row = fetch_article(st, slug, viewer).await?;
    Ok(Json(json!({"article": article_value(&row, true)})))
}

fn slugify(title: &str) -> String {
    let mut out = String::new();
    for c in title.chars() {
        if c.is_alphanumeric() {
            out.extend(c.to_lowercase());
        } else if !out.ends_with('-') && !out.is_empty() {
            out.push('-');
        }
    }
    let out = out.trim_end_matches('-').to_string();
    let out = if out.is_empty() { "article".to_string() } else { out };
    let suffix: String = (0..8).map(|_| char::from(b"abcdefghijklmnopqrstuvwxyz0123456789"[rand::thread_rng().gen_range(0..36)])).collect();
    format!("{out}-{suffix}")
}

fn parse_tags(m: &Map<String, Value>) -> ApiResult<Option<Vec<String>>> {
    match m.get("tagList") {
        None => Ok(None),
        Some(Value::Array(a)) => {
            let mut tags: Vec<String> = Vec::new();
            for t in a {
                match t {
                    Value::String(s) if !s.trim().is_empty() => {
                        if !tags.contains(s) { tags.push(s.clone()); }
                    }
                    _ => return Err(ApiError::unprocessable("tagList", "is invalid")),
                }
            }
            Ok(Some(tags))
        }
        _ => Err(ApiError::unprocessable("tagList", "is invalid")),
    }
}

pub async fn create_article(State(st): State<AppState>, AuthUser(uid): AuthUser, body: Bytes) -> ApiResult<(StatusCode, Json<Value>)> {
    let m = wrapped(&body, "article")?;
    let title = required(&m, "title")?;
    let description = required(&m, "description")?;
    let text = required(&m, "body")?;
    let tags = parse_tags(&m)?.unwrap_or_default();
    let slug = slugify(&title);
    let ts = now();
    let mut tx = st.db.begin().await?;
    let res = sqlx::query("INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .bind(&slug).bind(&title).bind(&description).bind(&text).bind(uid).bind(&ts).bind(&ts)
        .execute(&mut *tx).await?;
    let aid = res.last_insert_rowid();
    for (i, t) in tags.iter().enumerate() {
        sqlx::query("INSERT INTO article_tags (article_id, tag, position) VALUES (?, ?, ?)").bind(aid).bind(t).bind(i as i64).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    let Json(v) = single_article(&st, &slug, Some(uid)).await?;
    Ok((StatusCode::CREATED, Json(v)))
}

pub async fn get_article(State(st): State<AppState>, MaybeUser(v): MaybeUser, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    single_article(&st, &slug, v).await
}

pub async fn update_article(State(st): State<AppState>, AuthUser(uid): AuthUser, Path(slug): Path<String>, body: Bytes) -> ApiResult<Json<Value>> {
    let existing = fetch_article(&st, &slug, Some(uid)).await?;
    let aid: i64 = existing.get("id");
    if existing.get::<i64, _>("author_id") != uid {
        return Err(ApiError::forbidden("article"));
    }
    let m = wrapped(&body, "article")?;
    let title = present_nonblank(&m, "title")?;
    let description = present_nonblank(&m, "description")?;
    let text = present_nonblank(&m, "body")?;
    let tags = parse_tags(&m)?;
    let mut tx = st.db.begin().await?;
    let mut qb: QueryBuilder<Sqlite> = QueryBuilder::new("UPDATE articles SET updated_at = ");
    qb.push_bind(now());
    if let Some(v) = &title { qb.push(", title = ").push_bind(v); }
    if let Some(v) = &description { qb.push(", description = ").push_bind(v); }
    if let Some(v) = &text { qb.push(", body = ").push_bind(v); }
    qb.push(" WHERE id = ").push_bind(aid);
    qb.build().execute(&mut *tx).await?;
    if let Some(tags) = tags {
        sqlx::query("DELETE FROM article_tags WHERE article_id = ?").bind(aid).execute(&mut *tx).await?;
        for (i, t) in tags.iter().enumerate() {
            sqlx::query("INSERT INTO article_tags (article_id, tag, position) VALUES (?, ?, ?)").bind(aid).bind(t).bind(i as i64).execute(&mut *tx).await?;
        }
    }
    tx.commit().await?;
    single_article(&st, &slug, Some(uid)).await
}

pub async fn delete_article(State(st): State<AppState>, AuthUser(uid): AuthUser, Path(slug): Path<String>) -> ApiResult<StatusCode> {
    let row = fetch_article(&st, &slug, Some(uid)).await?;
    if row.get::<i64, _>("author_id") != uid {
        return Err(ApiError::forbidden("article"));
    }
    sqlx::query("DELETE FROM articles WHERE id = ?").bind(row.get::<i64, _>("id")).execute(&st.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize, Default)]
pub struct ListParams {
    tag: Option<String>,
    author: Option<String>,
    favorited: Option<String>,
    limit: Option<i64>,
    offset: Option<i64>,
}

async fn run_list(st: &AppState, p: &ListParams, viewer: Option<i64>, feed_for: Option<i64>) -> ApiResult<Json<Value>> {
    fn filters<'a>(qb: &mut QueryBuilder<'a, Sqlite>, p: &'a ListParams, feed_for: Option<i64>) {
        qb.push(" WHERE 1=1");
        if let Some(uid) = feed_for {
            qb.push(" AND a.author_id IN (SELECT followed_id FROM follows WHERE follower_id = ").push_bind(uid).push(")");
        }
        if let Some(t) = &p.tag {
            qb.push(" AND EXISTS(SELECT 1 FROM article_tags t WHERE t.article_id = a.id AND t.tag = ").push_bind(t).push(")");
        }
        if let Some(a) = &p.author {
            qb.push(" AND u.username = ").push_bind(a);
        }
        if let Some(f) = &p.favorited {
            qb.push(" AND EXISTS(SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id WHERE fv.article_id = a.id AND fu.username = ")
                .push_bind(f)
                .push(")");
        }
    }
    let limit = p.limit.unwrap_or(20).clamp(0, 100);
    let offset = p.offset.unwrap_or(0).max(0);
    let v = viewer.unwrap_or(-1);

    let mut count_qb: QueryBuilder<Sqlite> = QueryBuilder::new("SELECT COUNT(*) FROM articles a JOIN users u ON u.id = a.author_id");
    filters(&mut count_qb, p, feed_for);
    let total: i64 = count_qb.build_query_scalar().fetch_one(&st.db).await?;

    // Build the select with the viewer id bound for the two EXISTS subqueries.
    let (head, tail) = ARTICLE_SELECT.split_once("EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ?) AS favorited,").expect("select shape");
    let (mid, rest) = tail.split_once("w.follower_id = ?").expect("select shape");
    let mut qb: QueryBuilder<Sqlite> = QueryBuilder::new(head);
    qb.push("EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ").push_bind(v).push(") AS favorited,");
    qb.push(mid).push("w.follower_id = ").push_bind(v).push(rest);
    filters(&mut qb, p, feed_for);
    qb.push(" ORDER BY a.created_at DESC, a.id DESC LIMIT ").push_bind(limit).push(" OFFSET ").push_bind(offset);
    let rows = qb.build().fetch_all(&st.db).await?;
    let articles: Vec<Value> = rows.iter().map(|r| article_value(r, false)).collect();
    Ok(Json(json!({"articles": articles, "articlesCount": total})))
}

pub async fn list_articles(State(st): State<AppState>, MaybeUser(v): MaybeUser, Query(p): Query<ListParams>) -> ApiResult<Json<Value>> {
    run_list(&st, &p, v, None).await
}

pub async fn feed(State(st): State<AppState>, AuthUser(uid): AuthUser, Query(p): Query<ListParams>) -> ApiResult<Json<Value>> {
    run_list(&st, &p, Some(uid), Some(uid)).await
}

pub async fn favorite(State(st): State<AppState>, AuthUser(uid): AuthUser, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    let row = fetch_article(&st, &slug, Some(uid)).await?;
    sqlx::query("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)").bind(uid).bind(row.get::<i64, _>("id")).execute(&st.db).await?;
    single_article(&st, &slug, Some(uid)).await
}

pub async fn unfavorite(State(st): State<AppState>, AuthUser(uid): AuthUser, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    let row = fetch_article(&st, &slug, Some(uid)).await?;
    sqlx::query("DELETE FROM favorites WHERE user_id = ? AND article_id = ?").bind(uid).bind(row.get::<i64, _>("id")).execute(&st.db).await?;
    single_article(&st, &slug, Some(uid)).await
}

// ---------- comments ----------

async fn article_id(st: &AppState, slug: &str) -> ApiResult<i64> {
    sqlx::query_scalar("SELECT id FROM articles WHERE slug = ?")
        .bind(slug)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

const COMMENT_SELECT: &str = "SELECT c.id, c.body, c.created_at, c.updated_at, c.author_id, u.username, u.bio, u.image,
    EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followed_id = c.author_id) AS following
    FROM comments c JOIN users u ON u.id = c.author_id";

fn comment_value(r: &SqliteRow) -> Value {
    json!({
        "id": r.get::<i64, _>("id"),
        "body": r.get::<String, _>("body"),
        "createdAt": r.get::<String, _>("created_at"),
        "updatedAt": r.get::<String, _>("updated_at"),
        "author": {
            "username": r.get::<String, _>("username"),
            "bio": r.get::<Option<String>, _>("bio"),
            "image": r.get::<Option<String>, _>("image"),
            "following": r.get::<bool, _>("following"),
        },
    })
}

pub async fn list_comments(State(st): State<AppState>, MaybeUser(v): MaybeUser, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    let aid = article_id(&st, &slug).await?;
    let rows = sqlx::query(&format!("{COMMENT_SELECT} WHERE c.article_id = ? ORDER BY c.created_at DESC, c.id DESC"))
        .bind(v.unwrap_or(-1))
        .bind(aid)
        .fetch_all(&st.db)
        .await?;
    let comments: Vec<Value> = rows.iter().map(comment_value).collect();
    Ok(Json(json!({"comments": comments})))
}

pub async fn add_comment(State(st): State<AppState>, AuthUser(uid): AuthUser, Path(slug): Path<String>, body: Bytes) -> ApiResult<(StatusCode, Json<Value>)> {
    let aid = article_id(&st, &slug).await?;
    let m = wrapped(&body, "comment")?;
    let text = required(&m, "body")?;
    let ts = now();
    let res = sqlx::query("INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")
        .bind(aid).bind(uid).bind(&text).bind(&ts).bind(&ts)
        .execute(&st.db).await?;
    let row = sqlx::query(&format!("{COMMENT_SELECT} WHERE c.id = ?")).bind(uid).bind(res.last_insert_rowid()).fetch_one(&st.db).await?;
    Ok((StatusCode::CREATED, Json(json!({"comment": comment_value(&row)}))))
}

pub async fn update_comment(State(st): State<AppState>, AuthUser(uid): AuthUser, Path((slug, id)): Path<(String, i64)>, body: Bytes) -> ApiResult<Json<Value>> {
    let aid = article_id(&st, &slug).await?;
    let author: i64 = sqlx::query_scalar("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
        .bind(id)
        .bind(aid)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("comment"))?;
    if author != uid {
        return Err(ApiError::forbidden("comment"));
    }
    let m = wrapped(&body, "comment")?;
    let text = required(&m, "body")?;
    sqlx::query("UPDATE comments SET body = ?, updated_at = ? WHERE id = ?")
        .bind(&text).bind(now()).bind(id)
        .execute(&st.db).await?;
    let row = sqlx::query(&format!("{COMMENT_SELECT} WHERE c.id = ?")).bind(uid).bind(id).fetch_one(&st.db).await?;
    Ok(Json(json!({"comment": comment_value(&row)})))
}

pub async fn delete_comment(State(st): State<AppState>, AuthUser(uid): AuthUser, Path((slug, id)): Path<(String, i64)>) -> ApiResult<StatusCode> {
    let aid = article_id(&st, &slug).await?;
    let author: i64 = sqlx::query_scalar("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
        .bind(id)
        .bind(aid)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("comment"))?;
    if author != uid {
        return Err(ApiError::forbidden("comment"));
    }
    sqlx::query("DELETE FROM comments WHERE id = ?").bind(id).execute(&st.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- tags ----------

pub async fn tags(State(st): State<AppState>) -> ApiResult<Json<Value>> {
    let tags: Vec<String> = sqlx::query_scalar("SELECT tag FROM article_tags GROUP BY tag ORDER BY MAX(article_id) DESC, tag LIMIT 100")
        .fetch_all(&st.db)
        .await?;
    Ok(Json(json!({"tags": tags})))
}
