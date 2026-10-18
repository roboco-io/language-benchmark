use crate::{
    error::{ApiError, ApiResult},
    AppState,
};
use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    Json,
};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sqlx::Row;

// ---------- helpers ----------

fn now() -> String {
    chrono::Utc::now()
        .format("%Y-%m-%dT%H:%M:%S%.3fZ")
        .to_string()
}

#[derive(Serialize, Deserialize)]
struct Claims {
    sub: i64,
    exp: usize,
}

fn make_token(state: &AppState, user_id: i64) -> ApiResult<String> {
    let exp = (chrono::Utc::now() + chrono::Duration::days(30)).timestamp() as usize;
    encode(
        &Header::default(),
        &Claims { sub: user_id, exp },
        &EncodingKey::from_secret(state.jwt_secret.as_bytes()),
    )
    .map_err(|e| ApiError::Internal(e.to_string()))
}

async fn hash_password(password: String) -> ApiResult<String> {
    tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|h| h.to_string())
            .map_err(|e| ApiError::Internal(e.to_string()))
    })
    .await
    .map_err(|e| ApiError::Internal(e.to_string()))?
}

async fn verify_password(password: String, hash: String) -> bool {
    tokio::task::spawn_blocking(move || {
        PasswordHash::new(&hash)
            .map(|h| Argon2::default().verify_password(password.as_bytes(), &h).is_ok())
            .unwrap_or(false)
    })
    .await
    .unwrap_or(false)
}

/// Extracts the raw token from `Authorization: Token <jwt>` (or `Bearer`).
fn raw_token(headers: &HeaderMap) -> Option<&str> {
    let v = headers.get("authorization")?.to_str().ok()?;
    v.strip_prefix("Token ")
        .or_else(|| v.strip_prefix("Bearer "))
        .map(str::trim)
        .filter(|t| !t.is_empty())
}

fn token_user_id(state: &AppState, token: &str) -> Option<i64> {
    decode::<Claims>(
        token,
        &DecodingKey::from_secret(state.jwt_secret.as_bytes()),
        &Validation::default(),
    )
    .ok()
    .map(|d| d.claims.sub)
}

struct UserRow {
    id: i64,
    username: String,
    email: String,
    bio: Option<String>,
    image: Option<String>,
}

async fn load_user(state: &AppState, id: i64) -> ApiResult<Option<UserRow>> {
    let row = sqlx::query("SELECT id, username, email, bio, image FROM users WHERE id = ?")
        .bind(id)
        .fetch_optional(&state.db)
        .await?;
    Ok(row.map(|r| UserRow {
        id: r.get("id"),
        username: r.get("username"),
        email: r.get("email"),
        bio: r.get("bio"),
        image: r.get("image"),
    }))
}

async fn require_auth(state: &AppState, headers: &HeaderMap) -> ApiResult<UserRow> {
    let token = raw_token(headers).ok_or(ApiError::Unauthorized("token", "is missing"))?;
    let id = token_user_id(state, token).ok_or(ApiError::Unauthorized("token", "is invalid"))?;
    load_user(state, id)
        .await?
        .ok_or(ApiError::Unauthorized("token", "is invalid"))
}

/// Returns the viewer id, or -1 for anonymous/invalid credentials.
fn optional_viewer(state: &AppState, headers: &HeaderMap) -> i64 {
    raw_token(headers)
        .and_then(|t| token_user_id(state, t))
        .unwrap_or(-1)
}

fn user_json(state: &AppState, u: &UserRow) -> ApiResult<Value> {
    Ok(json!({ "user": {
        "email": u.email,
        "token": make_token(state, u.id)?,
        "username": u.username,
        "bio": u.bio,
        "image": u.image,
    }}))
}

fn parse_body(bytes: &Bytes, key: &'static str) -> ApiResult<serde_json::Map<String, Value>> {
    let v: Value = serde_json::from_slice(bytes)
        .map_err(|_| ApiError::Unprocessable(vec![("body", "is invalid".into())]))?;
    match v.get(key) {
        Some(Value::Object(m)) => Ok(m.clone()),
        _ => Err(ApiError::Unprocessable(vec![(key, "can't be blank".into())])),
    }
}

/// Reads a string field. `None` = absent, `Some(None)` = null / wrong type.
fn field(m: &serde_json::Map<String, Value>, k: &str) -> Option<Option<String>> {
    m.get(k).map(|v| v.as_str().map(str::to_owned))
}

fn blank(s: &Option<String>) -> bool {
    s.as_deref().map(|x| x.trim().is_empty()).unwrap_or(true)
}

fn validate_email(email: &str) -> bool {
    let mut parts = email.splitn(2, '@');
    matches!((parts.next(), parts.next()), (Some(l), Some(d)) if !l.is_empty() && d.contains('.') && !d.starts_with('.') && !d.ends_with('.') && !email.contains(' '))
}

// ---------- users ----------

pub async fn register(State(st): State<AppState>, body: Bytes) -> ApiResult<(StatusCode, Json<Value>)> {
    let m = parse_body(&body, "user")?;
    let username = field(&m, "username").unwrap_or(None);
    let email = field(&m, "email").unwrap_or(None);
    let password = field(&m, "password").unwrap_or(None);

    let mut errs = vec![];
    if blank(&username) {
        errs.push(("username", "can't be blank".to_string()));
    }
    if blank(&email) {
        errs.push(("email", "can't be blank".to_string()));
    } else if !validate_email(email.as_deref().unwrap()) {
        errs.push(("email", "is invalid".to_string()));
    }
    match password.as_deref() {
        None | Some("") => errs.push(("password", "can't be blank".to_string())),
        Some(p) if p.chars().count() < 8 => {
            errs.push(("password", "is too short (minimum is 8 characters)".to_string()))
        }
        _ => {}
    }
    if !errs.is_empty() {
        return Err(ApiError::Unprocessable(errs));
    }
    let (username, email, password) = (username.unwrap(), email.unwrap(), password.unwrap());

    let taken_name: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE username = ?")
        .bind(&username)
        .fetch_optional(&st.db)
        .await?;
    if taken_name.is_some() {
        return Err(ApiError::Conflict("username", "has already been taken"));
    }
    let taken_email: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&st.db)
        .await?;
    if taken_email.is_some() {
        return Err(ApiError::Conflict("email", "has already been taken"));
    }

    let hash = hash_password(password).await?;
    let res = sqlx::query("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)")
        .bind(&username)
        .bind(&email)
        .bind(&hash)
        .execute(&st.db)
        .await;
    let id = match res {
        Ok(r) => r.last_insert_rowid(),
        // A concurrent registration won the unique-index race.
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => {
            let msg = e.message().to_string();
            return Err(if msg.contains("email") {
                ApiError::Conflict("email", "has already been taken")
            } else {
                ApiError::Conflict("username", "has already been taken")
            });
        }
        Err(e) => return Err(e.into()),
    };
    let user = load_user(&st, id).await?.ok_or(ApiError::Internal("user vanished".into()))?;
    Ok((StatusCode::CREATED, Json(user_json(&st, &user)?)))
}

pub async fn login(State(st): State<AppState>, body: Bytes) -> ApiResult<Json<Value>> {
    let m = parse_body(&body, "user")?;
    let email = field(&m, "email").unwrap_or(None);
    let password = field(&m, "password").unwrap_or(None);
    let mut errs = vec![];
    if blank(&email) {
        errs.push(("email", "can't be blank".to_string()));
    }
    if password.as_deref().unwrap_or("").is_empty() {
        errs.push(("password", "can't be blank".to_string()));
    }
    if !errs.is_empty() {
        return Err(ApiError::Unprocessable(errs));
    }
    let row = sqlx::query("SELECT id, password_hash FROM users WHERE email = ?")
        .bind(email.unwrap())
        .fetch_optional(&st.db)
        .await?;
    let invalid = ApiError::Unauthorized("credentials", "invalid");
    let Some(row) = row else { return Err(invalid) };
    if !verify_password(password.unwrap(), row.get("password_hash")).await {
        return Err(invalid);
    }
    let user = load_user(&st, row.get("id")).await?.ok_or(invalid)?;
    Ok(Json(user_json(&st, &user)?))
}

pub async fn current_user(State(st): State<AppState>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let user = require_auth(&st, &headers).await?;
    Ok(Json(user_json(&st, &user)?))
}

pub async fn update_user(State(st): State<AppState>, headers: HeaderMap, body: Bytes) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    let m = parse_body(&body, "user")?;
    let mut errs = vec![];

    let mut username = me.username.clone();
    let mut email = me.email.clone();
    let mut bio = me.bio.clone();
    let mut image = me.image.clone();
    let mut new_hash = None;

    if let Some(v) = field(&m, "username") {
        match v {
            Some(s) if !s.trim().is_empty() => username = s,
            _ => errs.push(("username", "can't be blank".to_string())),
        }
    }
    if let Some(v) = field(&m, "email") {
        match v {
            Some(s) if !s.trim().is_empty() => {
                if validate_email(&s) {
                    email = s
                } else {
                    errs.push(("email", "is invalid".to_string()))
                }
            }
            _ => errs.push(("email", "can't be blank".to_string())),
        }
    }
    let mut password = None;
    if let Some(v) = field(&m, "password") {
        match v {
            Some(s) if s.is_empty() => errs.push(("password", "can't be blank".to_string())),
            Some(s) if s.chars().count() < 8 => {
                errs.push(("password", "is too short (minimum is 8 characters)".to_string()))
            }
            Some(s) => password = Some(s),
            None => errs.push(("password", "can't be blank".to_string())),
        }
    }
    if let Some(v) = field(&m, "bio") {
        bio = v.filter(|s| !s.is_empty());
    }
    if m.contains_key("image") {
        image = field(&m, "image").unwrap_or(None).filter(|s| !s.is_empty());
    }
    if !errs.is_empty() {
        return Err(ApiError::Unprocessable(errs));
    }
    if let Some(p) = password {
        new_hash = Some(hash_password(p).await?);
    }

    if username != me.username {
        let t: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE username = ? AND id != ?")
            .bind(&username).bind(me.id).fetch_optional(&st.db).await?;
        if t.is_some() {
            return Err(ApiError::Conflict("username", "has already been taken"));
        }
    }
    if email != me.email {
        let t: Option<i64> = sqlx::query_scalar("SELECT id FROM users WHERE email = ? AND id != ?")
            .bind(&email).bind(me.id).fetch_optional(&st.db).await?;
        if t.is_some() {
            return Err(ApiError::Conflict("email", "has already been taken"));
        }
    }

    let res = sqlx::query(
        "UPDATE users SET username = ?, email = ?, bio = ?, image = ?, \
         password_hash = COALESCE(?, password_hash) WHERE id = ?",
    )
    .bind(&username).bind(&email).bind(&bio).bind(&image).bind(&new_hash).bind(me.id)
    .execute(&st.db)
    .await;
    match res {
        Ok(_) => {}
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => {
            return Err(if e.message().contains("email") {
                ApiError::Conflict("email", "has already been taken")
            } else {
                ApiError::Conflict("username", "has already been taken")
            });
        }
        Err(e) => return Err(e.into()),
    }
    let user = load_user(&st, me.id).await?.ok_or(ApiError::Internal("user vanished".into()))?;
    Ok(Json(user_json(&st, &user)?))
}

// ---------- profiles ----------

async fn profile_json(st: &AppState, username: &str, viewer: i64) -> ApiResult<Value> {
    let row = sqlx::query(
        "SELECT u.username, u.bio, u.image, \
         EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followed_id = u.id) AS following \
         FROM users u WHERE u.username = ?",
    )
    .bind(viewer)
    .bind(username)
    .fetch_optional(&st.db)
    .await?
    .ok_or(ApiError::NotFound("profile"))?;
    Ok(json!({ "profile": {
        "username": row.get::<String, _>("username"),
        "bio": row.get::<Option<String>, _>("bio"),
        "image": row.get::<Option<String>, _>("image"),
        "following": row.get::<bool, _>("following"),
    }}))
}

pub async fn get_profile(State(st): State<AppState>, headers: HeaderMap, Path(username): Path<String>) -> ApiResult<Json<Value>> {
    let viewer = optional_viewer(&st, &headers);
    Ok(Json(profile_json(&st, &username, viewer).await?))
}

async fn user_id_by_name(st: &AppState, username: &str) -> ApiResult<i64> {
    sqlx::query_scalar("SELECT id FROM users WHERE username = ?")
        .bind(username)
        .fetch_optional(&st.db)
        .await?
        .ok_or(ApiError::NotFound("profile"))
}

pub async fn follow(State(st): State<AppState>, headers: HeaderMap, Path(username): Path<String>) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    let target = user_id_by_name(&st, &username).await?;
    sqlx::query("INSERT OR IGNORE INTO follows (follower_id, followed_id) VALUES (?, ?)")
        .bind(me.id).bind(target).execute(&st.db).await?;
    Ok(Json(profile_json(&st, &username, me.id).await?))
}

pub async fn unfollow(State(st): State<AppState>, headers: HeaderMap, Path(username): Path<String>) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    let target = user_id_by_name(&st, &username).await?;
    sqlx::query("DELETE FROM follows WHERE follower_id = ? AND followed_id = ?")
        .bind(me.id).bind(target).execute(&st.db).await?;
    Ok(Json(profile_json(&st, &username, me.id).await?))
}

// ---------- articles ----------

const ARTICLE_SELECT: &str = "SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at, \
    a.author_id, u.username, u.bio, u.image, \
    (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS fav_count, \
    EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ?1) AS favorited, \
    EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ?1 AND w.followed_id = a.author_id) AS following \
    FROM articles a JOIN users u ON u.id = a.author_id";

const ARTICLE_FILTER: &str = " WHERE (?2 IS NULL OR u.username = ?2) \
    AND (?3 IS NULL OR EXISTS(SELECT 1 FROM article_tags t WHERE t.article_id = a.id AND t.tag = ?3)) \
    AND (?4 IS NULL OR EXISTS(SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id \
        WHERE fv.article_id = a.id AND fu.username = ?4)) \
    AND (?5 = 0 OR EXISTS(SELECT 1 FROM follows fl WHERE fl.follower_id = ?1 AND fl.followed_id = a.author_id))";

async fn article_from_row(st: &AppState, r: &sqlx::sqlite::SqliteRow, with_body: bool) -> ApiResult<Value> {
    let id: i64 = r.get("id");
    let tags: Vec<String> = sqlx::query_scalar("SELECT tag FROM article_tags WHERE article_id = ? ORDER BY tag")
        .bind(id)
        .fetch_all(&st.db)
        .await?;
    let mut a = json!({
        "slug": r.get::<String, _>("slug"),
        "title": r.get::<String, _>("title"),
        "description": r.get::<String, _>("description"),
        "tagList": tags,
        "createdAt": r.get::<String, _>("created_at"),
        "updatedAt": r.get::<String, _>("updated_at"),
        "favorited": r.get::<bool, _>("favorited"),
        "favoritesCount": r.get::<i64, _>("fav_count"),
        "author": {
            "username": r.get::<String, _>("username"),
            "bio": r.get::<Option<String>, _>("bio"),
            "image": r.get::<Option<String>, _>("image"),
            "following": r.get::<bool, _>("following"),
        },
    });
    if with_body {
        a["body"] = json!(r.get::<String, _>("body"));
    }
    Ok(a)
}

async fn single_article(st: &AppState, slug: &str, viewer: i64) -> ApiResult<Value> {
    let sql = format!("{ARTICLE_SELECT} WHERE a.slug = ?2");
    let row = sqlx::query(&sql)
        .bind(viewer)
        .bind(slug)
        .fetch_optional(&st.db)
        .await?
        .ok_or(ApiError::NotFound("article"))?;
    Ok(json!({ "article": article_from_row(st, &row, true).await? }))
}

#[derive(Deserialize)]
pub struct ListParams {
    tag: Option<String>,
    author: Option<String>,
    favorited: Option<String>,
    limit: Option<i64>,
    offset: Option<i64>,
}

async fn query_articles(st: &AppState, viewer: i64, p: &ListParams, feed: bool) -> ApiResult<Json<Value>> {
    let limit = p.limit.unwrap_or(20).clamp(0, 100);
    let offset = p.offset.unwrap_or(0).max(0);
    let (author, tag, fav) = if feed {
        (None, None, None)
    } else {
        (p.author.as_deref(), p.tag.as_deref(), p.favorited.as_deref())
    };

    let count_sql = format!("SELECT COUNT(*) FROM articles a JOIN users u ON u.id = a.author_id{ARTICLE_FILTER}");
    let total: i64 = sqlx::query_scalar(&count_sql)
        .bind(viewer).bind(author).bind(tag).bind(fav).bind(feed as i64)
        .fetch_one(&st.db)
        .await?;

    let sql = format!("{ARTICLE_SELECT}{ARTICLE_FILTER} ORDER BY a.created_at DESC, a.id DESC LIMIT ?6 OFFSET ?7");
    let rows = sqlx::query(&sql)
        .bind(viewer).bind(author).bind(tag).bind(fav).bind(feed as i64)
        .bind(limit).bind(offset)
        .fetch_all(&st.db)
        .await?;
    let mut articles = Vec::with_capacity(rows.len());
    for r in &rows {
        articles.push(article_from_row(st, r, false).await?);
    }
    Ok(Json(json!({ "articles": articles, "articlesCount": total })))
}

pub async fn list_articles(State(st): State<AppState>, headers: HeaderMap, Query(p): Query<ListParams>) -> ApiResult<Json<Value>> {
    let viewer = optional_viewer(&st, &headers);
    query_articles(&st, viewer, &p, false).await
}

pub async fn feed_articles(State(st): State<AppState>, headers: HeaderMap, Query(p): Query<ListParams>) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    query_articles(&st, me.id, &p, true).await
}

pub async fn get_article(State(st): State<AppState>, headers: HeaderMap, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    let viewer = optional_viewer(&st, &headers);
    Ok(Json(single_article(&st, &slug, viewer).await?))
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
    let s = out.trim_end_matches('-');
    if s.is_empty() { "article".into() } else { s.to_string() }
}

fn parse_tags(m: &serde_json::Map<String, Value>) -> Option<Vec<String>> {
    m.get("tagList").and_then(|v| v.as_array()).map(|a| {
        let mut tags: Vec<String> = a
            .iter()
            .filter_map(|t| t.as_str())
            .map(|t| t.trim().to_string())
            .filter(|t| !t.is_empty())
            .collect();
        tags.sort();
        tags.dedup();
        tags
    })
}

pub async fn create_article(State(st): State<AppState>, headers: HeaderMap, body: Bytes) -> ApiResult<(StatusCode, Json<Value>)> {
    let me = require_auth(&st, &headers).await?;
    let m = parse_body(&body, "article")?;
    let title = field(&m, "title").unwrap_or(None);
    let description = field(&m, "description").unwrap_or(None);
    let text = field(&m, "body").unwrap_or(None);
    let mut errs = vec![];
    if blank(&title) { errs.push(("title", "can't be blank".to_string())); }
    if blank(&description) { errs.push(("description", "can't be blank".to_string())); }
    if blank(&text) { errs.push(("body", "can't be blank".to_string())); }
    if !errs.is_empty() {
        return Err(ApiError::Unprocessable(errs));
    }
    let (title, description, text) = (title.unwrap(), description.unwrap(), text.unwrap());
    let tags = parse_tags(&m).unwrap_or_default();
    let slug = format!("{}-{:08x}", slugify(&title), rand::thread_rng().gen::<u32>());
    let ts = now();

    let mut tx = st.db.begin().await?;
    let id = sqlx::query(
        "INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&slug).bind(&title).bind(&description).bind(&text).bind(me.id).bind(&ts).bind(&ts)
    .execute(&mut *tx)
    .await?
    .last_insert_rowid();
    for t in &tags {
        sqlx::query("INSERT INTO article_tags (article_id, tag) VALUES (?, ?)")
            .bind(id).bind(t).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    Ok((StatusCode::CREATED, Json(single_article(&st, &slug, me.id).await?)))
}

/// Loads (article id, author id) for a slug.
async fn article_owner(st: &AppState, slug: &str) -> ApiResult<(i64, i64)> {
    let row = sqlx::query("SELECT id, author_id FROM articles WHERE slug = ?")
        .bind(slug)
        .fetch_optional(&st.db)
        .await?
        .ok_or(ApiError::NotFound("article"))?;
    Ok((row.get("id"), row.get("author_id")))
}

pub async fn update_article(State(st): State<AppState>, headers: HeaderMap, Path(slug): Path<String>, body: Bytes) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    let (id, author) = article_owner(&st, &slug).await?;
    if author != me.id {
        return Err(ApiError::Forbidden("article"));
    }
    let m = parse_body(&body, "article")?;
    let mut errs = vec![];
    let mut get = |k: &'static str| -> Option<String> {
        match field(&m, k) {
            None => None,
            Some(Some(s)) if !s.trim().is_empty() => Some(s),
            Some(_) => { errs.push((k, "can't be blank".to_string())); None }
        }
    };
    let (title, description, text) = (get("title"), get("description"), get("body"));
    if !errs.is_empty() {
        return Err(ApiError::Unprocessable(errs));
    }
    if matches!(m.get("tagList"), Some(v) if !v.is_array()) {
        return Err(ApiError::Unprocessable(vec![("tagList", "is invalid".into())]));
    }
    let tags = parse_tags(&m);

    let mut tx = st.db.begin().await?;
    sqlx::query(
        "UPDATE articles SET title = COALESCE(?, title), description = COALESCE(?, description), \
         body = COALESCE(?, body), updated_at = ? WHERE id = ?",
    )
    .bind(&title).bind(&description).bind(&text).bind(now()).bind(id)
    .execute(&mut *tx)
    .await?;
    if let Some(tags) = tags {
        sqlx::query("DELETE FROM article_tags WHERE article_id = ?").bind(id).execute(&mut *tx).await?;
        for t in &tags {
            sqlx::query("INSERT INTO article_tags (article_id, tag) VALUES (?, ?)")
                .bind(id).bind(t).execute(&mut *tx).await?;
        }
    }
    tx.commit().await?;
    Ok(Json(single_article(&st, &slug, me.id).await?))
}

pub async fn delete_article(State(st): State<AppState>, headers: HeaderMap, Path(slug): Path<String>) -> ApiResult<StatusCode> {
    let me = require_auth(&st, &headers).await?;
    let (id, author) = article_owner(&st, &slug).await?;
    if author != me.id {
        return Err(ApiError::Forbidden("article"));
    }
    sqlx::query("DELETE FROM articles WHERE id = ?").bind(id).execute(&st.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- favorites ----------

pub async fn favorite(State(st): State<AppState>, headers: HeaderMap, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    let (id, _) = article_owner(&st, &slug).await?;
    sqlx::query("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)")
        .bind(me.id).bind(id).execute(&st.db).await?;
    Ok(Json(single_article(&st, &slug, me.id).await?))
}

pub async fn unfavorite(State(st): State<AppState>, headers: HeaderMap, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    let (id, _) = article_owner(&st, &slug).await?;
    sqlx::query("DELETE FROM favorites WHERE user_id = ? AND article_id = ?")
        .bind(me.id).bind(id).execute(&st.db).await?;
    Ok(Json(single_article(&st, &slug, me.id).await?))
}

// ---------- comments ----------

fn comment_json(r: &sqlx::sqlite::SqliteRow) -> Value {
    json!({
        "id": r.get::<i64, _>("id"),
        "createdAt": r.get::<String, _>("created_at"),
        "updatedAt": r.get::<String, _>("updated_at"),
        "body": r.get::<String, _>("body"),
        "author": {
            "username": r.get::<String, _>("username"),
            "bio": r.get::<Option<String>, _>("bio"),
            "image": r.get::<Option<String>, _>("image"),
            "following": r.get::<bool, _>("following"),
        },
    })
}

const COMMENT_SELECT: &str = "SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image, \
    EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ?1 AND w.followed_id = c.author_id) AS following \
    FROM comments c JOIN users u ON u.id = c.author_id";

pub async fn add_comment(State(st): State<AppState>, headers: HeaderMap, Path(slug): Path<String>, body: Bytes) -> ApiResult<(StatusCode, Json<Value>)> {
    let me = require_auth(&st, &headers).await?;
    let (article_id, _) = article_owner(&st, &slug).await?;
    let m = parse_body(&body, "comment")?;
    let text = field(&m, "body").unwrap_or(None);
    if blank(&text) {
        return Err(ApiError::blank("body"));
    }
    let ts = now();
    let id = sqlx::query(
        "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(article_id).bind(me.id).bind(text.unwrap()).bind(&ts).bind(&ts)
    .execute(&st.db)
    .await?
    .last_insert_rowid();
    let sql = format!("{COMMENT_SELECT} WHERE c.id = ?2");
    let row = sqlx::query(&sql).bind(me.id).bind(id).fetch_one(&st.db).await?;
    Ok((StatusCode::CREATED, Json(json!({ "comment": comment_json(&row) }))))
}

pub async fn list_comments(State(st): State<AppState>, headers: HeaderMap, Path(slug): Path<String>) -> ApiResult<Json<Value>> {
    let viewer = optional_viewer(&st, &headers);
    let (article_id, _) = article_owner(&st, &slug).await?;
    let sql = format!("{COMMENT_SELECT} WHERE c.article_id = ?2 ORDER BY c.id DESC");
    let rows = sqlx::query(&sql).bind(viewer).bind(article_id).fetch_all(&st.db).await?;
    let comments: Vec<Value> = rows.iter().map(comment_json).collect();
    Ok(Json(json!({ "comments": comments })))
}

pub async fn update_comment(State(st): State<AppState>, headers: HeaderMap, Path((slug, id)): Path<(String, i64)>, body: Bytes) -> ApiResult<Json<Value>> {
    let me = require_auth(&st, &headers).await?;
    let (article_id, _) = article_owner(&st, &slug).await?;
    let row = sqlx::query("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
        .bind(id).bind(article_id)
        .fetch_optional(&st.db)
        .await?
        .ok_or(ApiError::NotFound("comment"))?;
    if row.get::<i64, _>("author_id") != me.id {
        return Err(ApiError::Forbidden("comment"));
    }
    let m = parse_body(&body, "comment")?;
    let text = field(&m, "body").unwrap_or(None);
    if blank(&text) {
        return Err(ApiError::blank("body"));
    }
    sqlx::query("UPDATE comments SET body = ?, updated_at = ? WHERE id = ?")
        .bind(text.unwrap()).bind(now()).bind(id)
        .execute(&st.db)
        .await?;
    let sql = format!("{COMMENT_SELECT} WHERE c.id = ?2");
    let row = sqlx::query(&sql).bind(me.id).bind(id).fetch_one(&st.db).await?;
    Ok(Json(json!({ "comment": comment_json(&row) })))
}

pub async fn delete_comment(State(st): State<AppState>, headers: HeaderMap, Path((slug, id)): Path<(String, i64)>) -> ApiResult<StatusCode> {
    let me = require_auth(&st, &headers).await?;
    let (article_id, _) = article_owner(&st, &slug).await?;
    let row = sqlx::query("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
        .bind(id).bind(article_id)
        .fetch_optional(&st.db)
        .await?
        .ok_or(ApiError::NotFound("comment"))?;
    if row.get::<i64, _>("author_id") != me.id {
        return Err(ApiError::Forbidden("comment"));
    }
    sqlx::query("DELETE FROM comments WHERE id = ?").bind(id).execute(&st.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- tags ----------

pub async fn list_tags(State(st): State<AppState>) -> ApiResult<Json<Value>> {
    let tags: Vec<String> = sqlx::query_scalar("SELECT DISTINCT tag FROM article_tags ORDER BY tag")
        .fetch_all(&st.db)
        .await?;
    Ok(Json(json!({ "tags": tags })))
}

