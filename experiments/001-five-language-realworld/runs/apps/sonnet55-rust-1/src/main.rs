mod auth;
mod error;
mod handlers;

use axum::{
    routing::{delete, get, post},
    Router,
};
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous};
use std::{str::FromStr, time::Duration};

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::SqlitePool,
    pub jwt_secret: String,
}

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    bio TEXT,
    image TEXT
);
CREATE TABLE IF NOT EXISTS follows (
    follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (follower_id, followee_id)
);
CREATE TABLE IF NOT EXISTS articles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slug TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    body TEXT NOT NULL,
    author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_articles_author ON articles(author_id);
CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at DESC, id DESC);
CREATE TABLE IF NOT EXISTS article_tags (
    article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    tag TEXT NOT NULL,
    pos INTEGER NOT NULL,
    PRIMARY KEY (article_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_article_tags_tag ON article_tags(tag);
CREATE TABLE IF NOT EXISTS favorites (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, article_id)
);
CREATE INDEX IF NOT EXISTS idx_favorites_article ON favorites(article_id);
CREATE TABLE IF NOT EXISTS comments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    author_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_comments_article ON comments(article_id);
"#;

fn app(state: AppState) -> Router {
    use handlers::*;
    Router::new()
        .route("/api/users", post(register))
        .route("/api/users/login", post(login))
        .route("/api/user", get(current_user).put(update_user))
        .route("/api/profiles/{username}", get(get_profile))
        .route("/api/profiles/{username}/follow", post(follow).delete(unfollow))
        .route("/api/articles", get(list_articles).post(create_article))
        .route("/api/articles/feed", get(feed))
        .route(
            "/api/articles/{slug}",
            get(get_article).put(update_article).delete(delete_article),
        )
        .route(
            "/api/articles/{slug}/comments",
            get(list_comments).post(add_comment),
        )
        .route("/api/articles/{slug}/comments/{id}", delete(delete_comment))
        .route(
            "/api/articles/{slug}/favorite",
            post(favorite).delete(unfavorite),
        )
        .route("/api/tags", get(tags))
        .with_state(state)
}

async fn run(cores: usize) {
    let db_path = std::env::var("DATABASE_PATH").unwrap_or_else(|_| "realworld.db".into());
    let opts = SqliteConnectOptions::from_str(&format!("sqlite://{db_path}"))
        .expect("invalid DATABASE_PATH")
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .synchronous(SqliteSynchronous::Normal)
        .busy_timeout(Duration::from_millis(5000))
        .foreign_keys(true);
    let db = SqlitePoolOptions::new()
        .max_connections((cores as u32 * 2).max(4))
        .connect_with(opts)
        .await
        .expect("failed to open database");
    sqlx::raw_sql(SCHEMA).execute(&db).await.expect("failed to create schema");

    let state = AppState {
        db,
        jwt_secret: std::env::var("JWT_SECRET").unwrap_or_else(|_| "realworld-dev-secret-change-me".into()),
    };
    let port = std::env::var("PORT").unwrap_or_else(|_| "8000".into());
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}"))
        .await
        .expect("failed to bind port");
    println!("listening on 0.0.0.0:{port} with {cores} worker threads");
    axum::serve(listener, app(state)).await.expect("server error");
}

fn main() {
    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(cores)
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
        .block_on(run(cores));
}
