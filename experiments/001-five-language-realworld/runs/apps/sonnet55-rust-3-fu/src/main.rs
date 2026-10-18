mod auth;
mod error;
mod routes;

use axum::{routing::{get, post, put}, Router};
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous};
use std::{str::FromStr, time::Duration};

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::SqlitePool,
    pub jwt_secret: String,
}

const SCHEMA: &str = include_str!("schema.sql");

pub async fn connect(url: &str, max_conns: u32) -> Result<sqlx::SqlitePool, sqlx::Error> {
    let opts = SqliteConnectOptions::from_str(url)?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .synchronous(SqliteSynchronous::Normal)
        .busy_timeout(Duration::from_millis(5000))
        .foreign_keys(true);
    let pool = SqlitePoolOptions::new().max_connections(max_conns).connect_with(opts).await?;
    sqlx::raw_sql(SCHEMA).execute(&pool).await?;
    Ok(pool)
}

fn app(state: AppState) -> Router {
    use routes::*;
    Router::new()
        .route("/api/users", post(register))
        .route("/api/users/login", post(login))
        .route("/api/user", get(current_user).put(update_user))
        .route("/api/profiles/{username}", get(get_profile))
        .route("/api/profiles/{username}/follow", post(follow).delete(unfollow))
        .route("/api/articles", get(list_articles).post(create_article))
        .route("/api/articles/feed", get(feed))
        .route("/api/articles/{slug}", get(get_article).put(update_article).delete(delete_article))
        .route("/api/articles/{slug}/favorite", post(favorite).delete(unfavorite))
        .route("/api/articles/{slug}/comments", get(list_comments).post(add_comment))
        .route("/api/articles/{slug}/comments/{id}", put(update_comment).delete(delete_comment))
        .route("/api/tags", get(tags))
        .layer(tower_http::cors::CorsLayer::permissive())
        .with_state(state)
}

fn main() {
    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(cores)
        .enable_all()
        .build()
        .expect("tokio runtime")
        .block_on(run(cores));
}

async fn run(cores: usize) {
    let db_url = std::env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite://realworld.db".into());
    let port = std::env::var("PORT").unwrap_or_else(|_| "8000".into());
    let jwt_secret = std::env::var("JWT_SECRET").unwrap_or_else(|_| "dev-secret-change-me".into());
    let pool = connect(&db_url, (cores as u32 * 2).max(8)).await.expect("database");
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}")).await.expect("bind");
    println!("listening on 0.0.0.0:{port} with {cores} worker threads");
    axum::serve(listener, app(AppState { db: pool, jwt_secret })).await.expect("serve");
}
