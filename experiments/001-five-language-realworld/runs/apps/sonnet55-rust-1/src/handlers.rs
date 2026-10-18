use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use serde_json::{json, Map, Value};
use sqlx::{sqlite::SqliteRow, QueryBuilder, Row, Sqlite};
use std::collections::HashMap;

use crate::{
    auth::{check_password, hash_password, make_token, AuthUser, MaybeAuth},
    error::{ApiError, ApiResult},
    AppState,
};

// ---------- helpers ----------

fn now() -> String {
    chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

/// Parses the request body and returns the object nested under `key`.
fn parse_body(body: &Bytes, key: &str) -> ApiResult<Map<String, Value>> {
    let v: Value = serde_json::from_slice(body)
        .map_err(|_| ApiError::unprocessable("body", "is not valid JSON"))?;
    match v.get(key) {
        Some(Value::Object(m)) => Ok(m.clone()),
        _ => Err(ApiError::blank(key)),
    }
}

/// Required, non-blank string field.
fn req_str(m: &Map<String, Value>, field: &str) -> ApiResult<String> {
    match m.get(field) {
        Some(Value::String(s)) if !s.trim().is_empty() => Ok(s.clone()),
        Some(Value::String(_)) | Some(Value::Null) | None => Err(ApiError::blank(field)),
        _ => Err(ApiError::unprocessable(field, "is invalid")),
    }
}

/// Optional field on update: absent -> None; present must be a non-blank string.
fn opt_str(m: &Map<String, Value>, field: &str) -> ApiResult<Option<String>> {
    if m.contains_key(field) {
        req_str(m, field).map(Some)
    } else {
        Ok(None)
    }
}

/// Nullable field: absent -> no change; null or "" -> NULL.
fn nullable(m: &Map<String, Value>, field: &str) -> ApiResult<Option<Option<String>>> {
    match m.get(field) {
        None => Ok(None),
        Some(Value::Null) => Ok(Some(None)),
        Some(Value::String(s)) if s.is_empty() => Ok(Some(None)),
        Some(Value::String(s)) => Ok(Some(Some(s.clone()))),
        _ => Err(ApiError::unprocessable(field, "is invalid")),
    }
}

fn check_password_policy(pw: &str) -> ApiResult<()> {
    if pw.chars().count() < 8 {
        return Err(ApiError::unprocessable("password", "is too short (minimum is 8 characters)"));
    }
    Ok(())
}

fn tag_list(m: &Map<String, Value>) -> ApiResult<Option<Vec<String>>> {
    match m.get("tagList") {
        None => Ok(None),
        Some(Value::Array(a)) => {
            let mut out: Vec<String> = Vec::new();
            for t in a {
                match t {
                    Value::String(s) if !s.trim().is_empty() => {
                        if !out.contains(s) {
                            out.push(s.clone());
                        }
                    }
                    _ => return Err(ApiError::unprocessable("tagList", "is invalid")),
                }
            }
            Ok(Some(out))
        }
        _ => Err(ApiError::unprocessable("tagList", "is invalid")),
    }
}

fn slugify(title: &str) -> String {
    let mut s = String::new();
    for c in title.chars() {
        if c.is_alphanumeric() {
            s.extend(c.to_lowercase());
        } else if !s.ends_with('-') && !s.is_empty() {
            s.push('-');
        }
    }
    let s = s.trim_end_matches('-').to_string();
    if s.is_empty() { "article".into() } else { s }
}

fn user_json(row: &SqliteRow, token: &str) -> Value {
    json!({ "user": {
        "email": row.get::<String, _>("email"),
        "token": token,
        "username": row.get::<String, _>("username"),
        "bio": row.get::<Option<String>, _>("bio"),
        "image": row.get::<Option<String>, _>("image"),
    }})
}

fn profile_json(username: String, bio: Option<String>, image: Option<String>, following: bool) -> Value {
    json!({ "username": username, "bio": bio, "image": image, "following": following })
}

// ---------- users ----------

pub async fn register(State(st): State<AppState>, body: Bytes) -> ApiResult<(StatusCode, Json<Value>)> {
    let m = parse_body(&body, "user")?;
    let username = req_str(&m, "username")?;
    let email = req_str(&m, "email")?;
    let password = req_str(&m, "password")?;
    check_password_policy(&password)?;

    let taken = |col: &'static str, val: String| {
        let db = st.db.clone();
        async move {
            sqlx::query(&format!("SELECT 1 FROM users WHERE {col} = ?"))
                .bind(val)
                .fetch_optional(&db)
                .await
                .map(|r| r.is_some())
        }
    };
    if taken("username", username.clone()).await? {
        return Err(ApiError::conflict("username"));
    }
    if taken("email", email.clone()).await? {
        return Err(ApiError::conflict("email"));
    }

    let hash = tokio::task::spawn_blocking(move || hash_password(&password)).await.unwrap();
    let res = sqlx::query("INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)")
        .bind(&username)
        .bind(&email)
        .bind(hash)
        .execute(&st.db)
        .await;
    let id = match res {
        Ok(r) => r.last_insert_rowid(),
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => {
            let field = if e.message().contains("email") { "email" } else { "username" };
            return Err(ApiError::conflict(field));
        }
        Err(e) => return Err(e.into()),
    };
    let row = sqlx::query("SELECT * FROM users WHERE id = ?").bind(id).fetch_one(&st.db).await?;
    Ok((StatusCode::CREATED, Json(user_json(&row, &make_token(&st.jwt_secret, id)))))
}

pub async fn login(State(st): State<AppState>, body: Bytes) -> ApiResult<Json<Value>> {
    let m = parse_body(&body, "user")?;
    let email = req_str(&m, "email")?;
    let password = req_str(&m, "password")?;
    let row = sqlx::query("SELECT * FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::unauthorized("credentials", "invalid"))?;
    let hash: String = row.get("password_hash");
    let ok = tokio::task::spawn_blocking(move || check_password(&password, &hash)).await.unwrap();
    if !ok {
        return Err(ApiError::unauthorized("credentials", "invalid"));
    }
    let id: i64 = row.get("id");
    Ok(Json(user_json(&row, &make_token(&st.jwt_secret, id))))
}

pub async fn current_user(State(st): State<AppState>, auth: AuthUser) -> ApiResult<Json<Value>> {
    let row = sqlx::query("SELECT * FROM users WHERE id = ?")
        .bind(auth.id)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;
    Ok(Json(user_json(&row, &auth.token)))
}

pub async fn update_user(State(st): State<AppState>, auth: AuthUser, body: Bytes) -> ApiResult<Json<Value>> {
    let m = parse_body(&body, "user")?;
    let username = opt_str(&m, "username")?;
    let email = opt_str(&m, "email")?;
    let password = opt_str(&m, "password")?;
    if let Some(p) = &password {
        check_password_policy(p)?;
    }
    let bio = nullable(&m, "bio")?;
    let image = nullable(&m, "image")?;

    let cur = sqlx::query("SELECT * FROM users WHERE id = ?")
        .bind(auth.id)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::unauthorized("token", "is invalid"))?;

    let new_username = username.unwrap_or_else(|| cur.get("username"));
    let new_email = email.unwrap_or_else(|| cur.get("email"));
    let new_bio = bio.unwrap_or_else(|| cur.get("bio"));
    let new_image = image.unwrap_or_else(|| cur.get("image"));
    let new_hash = match password {
        Some(p) => tokio::task::spawn_blocking(move || hash_password(&p)).await.unwrap(),
        None => cur.get("password_hash"),
    };

    let res = sqlx::query(
        "UPDATE users SET username = ?, email = ?, password_hash = ?, bio = ?, image = ? WHERE id = ?",
    )
    .bind(&new_username)
    .bind(&new_email)
    .bind(new_hash)
    .bind(new_bio)
    .bind(new_image)
    .bind(auth.id)
    .execute(&st.db)
    .await;
    match res {
        Ok(_) => {}
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => {
            let field = if e.message().contains("email") { "email" } else { "username" };
            return Err(ApiError::conflict(field));
        }
        Err(e) => return Err(e.into()),
    }
    let row = sqlx::query("SELECT * FROM users WHERE id = ?").bind(auth.id).fetch_one(&st.db).await?;
    Ok(Json(user_json(&row, &auth.token)))
}

// ---------- profiles ----------

async fn profile_response(st: &AppState, username: &str, viewer: Option<i64>) -> ApiResult<Value> {
    let row = sqlx::query(
        "SELECT u.username, u.bio, u.image, \
         EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS following \
         FROM users u WHERE u.username = ?",
    )
    .bind(viewer)
    .bind(username)
    .fetch_optional(&st.db)
    .await?
    .ok_or_else(|| ApiError::not_found("profile"))?;
    Ok(json!({ "profile": profile_json(
        row.get("username"), row.get("bio"), row.get("image"), row.get::<bool, _>("following")) }))
}

pub async fn get_profile(
    State(st): State<AppState>,
    MaybeAuth(viewer): MaybeAuth,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    Ok(Json(profile_response(&st, &username, viewer).await?))
}

async fn user_id_by_name(st: &AppState, username: &str) -> ApiResult<i64> {
    sqlx::query_scalar("SELECT id FROM users WHERE username = ?")
        .bind(username)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("profile"))
}

pub async fn follow(
    State(st): State<AppState>,
    auth: AuthUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    let target = user_id_by_name(&st, &username).await?;
    sqlx::query("INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)")
        .bind(auth.id)
        .bind(target)
        .execute(&st.db)
        .await?;
    Ok(Json(profile_response(&st, &username, Some(auth.id)).await?))
}

pub async fn unfollow(
    State(st): State<AppState>,
    auth: AuthUser,
    Path(username): Path<String>,
) -> ApiResult<Json<Value>> {
    let target = user_id_by_name(&st, &username).await?;
    sqlx::query("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?")
        .bind(auth.id)
        .bind(target)
        .execute(&st.db)
        .await?;
    Ok(Json(profile_response(&st, &username, Some(auth.id)).await?))
}

// ---------- articles ----------

const ARTICLE_SELECT: &str = "SELECT a.id, a.slug, a.title, a.description, a.body, a.created_at, a.updated_at, \
     a.author_id, u.username, u.bio, u.image, \
     (SELECT COUNT(*) FROM favorites f WHERE f.article_id = a.id) AS fav_count, \
     EXISTS(SELECT 1 FROM favorites f WHERE f.article_id = a.id AND f.user_id = ";

/// Pushes the SELECT list and FROM clause; `viewer` is bound for the favorited/following flags.
fn push_article_select<'a>(qb: &mut QueryBuilder<'a, Sqlite>, viewer: Option<i64>) {
    qb.push(ARTICLE_SELECT);
    qb.push_bind(viewer);
    qb.push(") AS favorited, EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ");
    qb.push_bind(viewer);
    qb.push(" AND w.followee_id = a.author_id) AS following FROM articles a JOIN users u ON u.id = a.author_id");
}

async fn article_value(st: &AppState, row: &SqliteRow, with_body: bool) -> ApiResult<Value> {
    let id: i64 = row.get("id");
    let tags: Vec<String> = sqlx::query_scalar("SELECT tag FROM article_tags WHERE article_id = ? ORDER BY pos")
        .bind(id)
        .fetch_all(&st.db)
        .await?;
    let mut a = json!({
        "slug": row.get::<String, _>("slug"),
        "title": row.get::<String, _>("title"),
        "description": row.get::<String, _>("description"),
        "tagList": tags,
        "createdAt": row.get::<String, _>("created_at"),
        "updatedAt": row.get::<String, _>("updated_at"),
        "favorited": row.get::<bool, _>("favorited"),
        "favoritesCount": row.get::<i64, _>("fav_count"),
        "author": profile_json(row.get("username"), row.get("bio"), row.get("image"), row.get::<bool, _>("following")),
    });
    if with_body {
        a["body"] = json!(row.get::<String, _>("body"));
    }
    Ok(a)
}

async fn fetch_article_row(st: &AppState, slug: &str, viewer: Option<i64>) -> ApiResult<SqliteRow> {
    let mut qb = QueryBuilder::new("");
    push_article_select(&mut qb, viewer);
    qb.push(" WHERE a.slug = ").push_bind(slug.to_string());
    qb.build()
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

async fn single_article(st: &AppState, slug: &str, viewer: Option<i64>) -> ApiResult<Json<Value>> {
    let row = fetch_article_row(st, slug, viewer).await?;
    Ok(Json(json!({ "article": article_value(st, &row, true).await? })))
}

#[derive(Default)]
struct Filters {
    tag: Option<String>,
    author: Option<String>,
    favorited: Option<String>,
    feed_for: Option<i64>,
}

fn push_where(qb: &mut QueryBuilder<'_, Sqlite>, f: &Filters) {
    qb.push(" WHERE 1 = 1");
    if let Some(t) = &f.tag {
        qb.push(" AND EXISTS(SELECT 1 FROM article_tags t WHERE t.article_id = a.id AND t.tag = ")
            .push_bind(t.clone())
            .push(")");
    }
    if let Some(a) = &f.author {
        qb.push(" AND u.username = ").push_bind(a.clone());
    }
    if let Some(n) = &f.favorited {
        qb.push(" AND EXISTS(SELECT 1 FROM favorites fv JOIN users fu ON fu.id = fv.user_id WHERE fv.article_id = a.id AND fu.username = ")
            .push_bind(n.clone())
            .push(")");
    }
    if let Some(uid) = f.feed_for {
        qb.push(" AND a.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ")
            .push_bind(uid)
            .push(")");
    }
}

async fn query_articles(
    st: &AppState,
    viewer: Option<i64>,
    f: Filters,
    q: &HashMap<String, String>,
) -> ApiResult<Json<Value>> {
    let limit = q.get("limit").and_then(|v| v.parse::<i64>().ok()).filter(|v| *v >= 0).unwrap_or(20).min(100);
    let offset = q.get("offset").and_then(|v| v.parse::<i64>().ok()).filter(|v| *v >= 0).unwrap_or(0);

    let mut cq = QueryBuilder::new("SELECT COUNT(*) FROM articles a JOIN users u ON u.id = a.author_id");
    push_where(&mut cq, &f);
    let count: i64 = cq.build_query_scalar().fetch_one(&st.db).await?;

    let mut qb = QueryBuilder::new("");
    push_article_select(&mut qb, viewer);
    push_where(&mut qb, &f);
    qb.push(" ORDER BY a.created_at DESC, a.id DESC LIMIT ")
        .push_bind(limit)
        .push(" OFFSET ")
        .push_bind(offset);
    let rows = qb.build().fetch_all(&st.db).await?;
    let mut articles = Vec::with_capacity(rows.len());
    for r in &rows {
        articles.push(article_value(st, r, false).await?);
    }
    Ok(Json(json!({ "articles": articles, "articlesCount": count })))
}

pub async fn list_articles(
    State(st): State<AppState>,
    MaybeAuth(viewer): MaybeAuth,
    Query(q): Query<HashMap<String, String>>,
) -> ApiResult<Json<Value>> {
    let f = Filters {
        tag: q.get("tag").cloned(),
        author: q.get("author").cloned(),
        favorited: q.get("favorited").cloned(),
        feed_for: None,
    };
    query_articles(&st, viewer, f, &q).await
}

pub async fn feed(
    State(st): State<AppState>,
    auth: AuthUser,
    Query(q): Query<HashMap<String, String>>,
) -> ApiResult<Json<Value>> {
    let f = Filters { feed_for: Some(auth.id), ..Default::default() };
    query_articles(&st, Some(auth.id), f, &q).await
}

pub async fn get_article(
    State(st): State<AppState>,
    MaybeAuth(viewer): MaybeAuth,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    single_article(&st, &slug, viewer).await
}

async fn replace_tags(
    tx: &mut sqlx::SqliteConnection,
    article_id: i64,
    tags: &[String],
) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM article_tags WHERE article_id = ?").bind(article_id).execute(&mut *tx).await?;
    for (i, t) in tags.iter().enumerate() {
        sqlx::query("INSERT INTO article_tags (article_id, tag, pos) VALUES (?, ?, ?)")
            .bind(article_id)
            .bind(t)
            .bind(i as i64)
            .execute(&mut *tx)
            .await?;
    }
    Ok(())
}

pub async fn create_article(
    State(st): State<AppState>,
    auth: AuthUser,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let m = parse_body(&body, "article")?;
    let title = req_str(&m, "title")?;
    let description = req_str(&m, "description")?;
    let article_body = req_str(&m, "body")?;
    let tags = tag_list(&m)?.unwrap_or_default();

    let slug = format!("{}-{}", slugify(&title), &uuid::Uuid::new_v4().simple().to_string()[..8]);
    let ts = now();
    let mut tx = st.db.begin().await?;
    let id = sqlx::query(
        "INSERT INTO articles (slug, title, description, body, author_id, created_at, updated_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&slug)
    .bind(&title)
    .bind(&description)
    .bind(&article_body)
    .bind(auth.id)
    .bind(&ts)
    .bind(&ts)
    .execute(&mut *tx)
    .await?
    .last_insert_rowid();
    replace_tags(&mut tx, id, &tags).await?;
    tx.commit().await?;
    let Json(v) = single_article(&st, &slug, Some(auth.id)).await?;
    Ok((StatusCode::CREATED, Json(v)))
}

/// Loads an article's id and author, returning 404 if missing and 403 if `uid` is not the author.
async fn owned_article(st: &AppState, slug: &str, uid: i64) -> ApiResult<i64> {
    let row = sqlx::query("SELECT id, author_id FROM articles WHERE slug = ?")
        .bind(slug)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))?;
    if row.get::<i64, _>("author_id") != uid {
        return Err(ApiError::forbidden("article"));
    }
    Ok(row.get("id"))
}

pub async fn update_article(
    State(st): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let m = parse_body(&body, "article")?;
    let title = opt_str(&m, "title")?;
    let description = opt_str(&m, "description")?;
    let article_body = opt_str(&m, "body")?;
    let tags = tag_list(&m)?;
    let id = owned_article(&st, &slug, auth.id).await?;

    let mut tx = st.db.begin().await?;
    sqlx::query(
        "UPDATE articles SET title = COALESCE(?, title), description = COALESCE(?, description), \
         body = COALESCE(?, body), updated_at = ? WHERE id = ?",
    )
    .bind(title)
    .bind(description)
    .bind(article_body)
    .bind(now())
    .bind(id)
    .execute(&mut *tx)
    .await?;
    if let Some(t) = tags {
        replace_tags(&mut tx, id, &t).await?;
    }
    tx.commit().await?;
    single_article(&st, &slug, Some(auth.id)).await
}

pub async fn delete_article(
    State(st): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<StatusCode> {
    let id = owned_article(&st, &slug, auth.id).await?;
    sqlx::query("DELETE FROM articles WHERE id = ?").bind(id).execute(&st.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- favorites ----------

async fn article_id(st: &AppState, slug: &str) -> ApiResult<i64> {
    sqlx::query_scalar("SELECT id FROM articles WHERE slug = ?")
        .bind(slug)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("article"))
}

pub async fn favorite(
    State(st): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let id = article_id(&st, &slug).await?;
    sqlx::query("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)")
        .bind(auth.id)
        .bind(id)
        .execute(&st.db)
        .await?;
    single_article(&st, &slug, Some(auth.id)).await
}

pub async fn unfavorite(
    State(st): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let id = article_id(&st, &slug).await?;
    sqlx::query("DELETE FROM favorites WHERE user_id = ? AND article_id = ?")
        .bind(auth.id)
        .bind(id)
        .execute(&st.db)
        .await?;
    single_article(&st, &slug, Some(auth.id)).await
}

// ---------- comments ----------

fn comment_value(row: &SqliteRow) -> Value {
    json!({
        "id": row.get::<i64, _>("id"),
        "createdAt": row.get::<String, _>("created_at"),
        "updatedAt": row.get::<String, _>("updated_at"),
        "body": row.get::<String, _>("body"),
        "author": profile_json(row.get("username"), row.get("bio"), row.get("image"), row.get::<bool, _>("following")),
    })
}

const COMMENT_SELECT: &str = "SELECT c.id, c.body, c.created_at, c.updated_at, u.username, u.bio, u.image, \
     EXISTS(SELECT 1 FROM follows w WHERE w.follower_id = ? AND w.followee_id = c.author_id) AS following \
     FROM comments c JOIN users u ON u.id = c.author_id";

pub async fn list_comments(
    State(st): State<AppState>,
    MaybeAuth(viewer): MaybeAuth,
    Path(slug): Path<String>,
) -> ApiResult<Json<Value>> {
    let aid = article_id(&st, &slug).await?;
    let rows = sqlx::query(&format!("{COMMENT_SELECT} WHERE c.article_id = ? ORDER BY c.created_at DESC, c.id DESC"))
        .bind(viewer)
        .bind(aid)
        .fetch_all(&st.db)
        .await?;
    let comments: Vec<Value> = rows.iter().map(comment_value).collect();
    Ok(Json(json!({ "comments": comments })))
}

pub async fn add_comment(
    State(st): State<AppState>,
    auth: AuthUser,
    Path(slug): Path<String>,
    body: Bytes,
) -> ApiResult<(StatusCode, Json<Value>)> {
    let m = parse_body(&body, "comment")?;
    let text = req_str(&m, "body")?;
    let aid = article_id(&st, &slug).await?;
    let ts = now();
    let cid = sqlx::query(
        "INSERT INTO comments (article_id, author_id, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(aid)
    .bind(auth.id)
    .bind(text)
    .bind(&ts)
    .bind(&ts)
    .execute(&st.db)
    .await?
    .last_insert_rowid();
    let row = sqlx::query(&format!("{COMMENT_SELECT} WHERE c.id = ?"))
        .bind(auth.id)
        .bind(cid)
        .fetch_one(&st.db)
        .await?;
    Ok((StatusCode::CREATED, Json(json!({ "comment": comment_value(&row) }))))
}

pub async fn delete_comment(
    State(st): State<AppState>,
    auth: AuthUser,
    Path((slug, cid)): Path<(String, String)>,
) -> ApiResult<StatusCode> {
    let aid = article_id(&st, &slug).await?;
    let cid: i64 = cid.parse().map_err(|_| ApiError::not_found("comment"))?;
    let author: i64 = sqlx::query_scalar("SELECT author_id FROM comments WHERE id = ? AND article_id = ?")
        .bind(cid)
        .bind(aid)
        .fetch_optional(&st.db)
        .await?
        .ok_or_else(|| ApiError::not_found("comment"))?;
    if author != auth.id {
        return Err(ApiError::forbidden("comment"));
    }
    sqlx::query("DELETE FROM comments WHERE id = ?").bind(cid).execute(&st.db).await?;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- tags ----------

pub async fn tags(State(st): State<AppState>) -> ApiResult<Json<Value>> {
    let tags: Vec<String> = sqlx::query_scalar("SELECT DISTINCT tag FROM article_tags ORDER BY tag")
        .fetch_all(&st.db)
        .await?;
    Ok(Json(json!({ "tags": tags })))
}
