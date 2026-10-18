mod error;
mod handlers;

use axum::{
    routing::{get, post},
    Router,
};
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous};
use std::{str::FromStr, time::Duration};

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::SqlitePool,
    pub jwt_secret: std::sync::Arc<String>,
}

fn app(state: AppState) -> Router {
    use handlers::*;
    Router::new()
        .route("/api/users", post(register))
        .route("/api/users/login", post(login))
        .route("/api/user", get(current_user).put(update_user))
        .route("/api/profiles/{username}", get(get_profile))
        .route(
            "/api/profiles/{username}/follow",
            post(follow).delete(unfollow),
        )
        .route("/api/articles", get(list_articles).post(create_article))
        .route("/api/articles/feed", get(feed_articles))
        .route(
            "/api/articles/{slug}",
            get(get_article).put(update_article).delete(delete_article),
        )
        .route(
            "/api/articles/{slug}/comments",
            get(list_comments).post(add_comment),
        )
        .route(
            "/api/articles/{slug}/comments/{id}",
            axum::routing::delete(delete_comment),
        )
        .route(
            "/api/articles/{slug}/favorite",
            post(favorite).delete(unfavorite),
        )
        .route("/api/tags", get(list_tags))
        .with_state(state)
}

async fn shutdown_signal() {
    use tokio::signal::unix::{signal, SignalKind};
    let mut term = signal(SignalKind::terminate()).expect("SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}

async fn run(workers: usize) -> Result<(), Box<dyn std::error::Error>> {
    let database_url =
        std::env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite://realworld.db".to_string());
    let opts = SqliteConnectOptions::from_str(&database_url)?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .synchronous(SqliteSynchronous::Normal)
        .busy_timeout(Duration::from_millis(5000))
        .foreign_keys(true);
    let db = SqlitePoolOptions::new()
        .max_connections((workers as u32).max(4))
        .connect_with(opts)
        .await?;
    sqlx::raw_sql(include_str!("../migrations/schema.sql"))
        .execute(&db)
        .await?;

    let jwt_secret =
        std::env::var("JWT_SECRET").unwrap_or_else(|_| "change-me-realworld-secret".to_string());
    let state = AppState {
        db,
        jwt_secret: std::sync::Arc::new(jwt_secret),
    };

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8000);
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await?;
    println!("listening on 0.0.0.0:{port} with {workers} worker threads");
    axum::serve(listener, app(state))
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    Ok(())
}

fn main() {
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(workers)
        .enable_all()
        .build()
        .expect("failed to build tokio runtime");
    if let Err(e) = rt.block_on(run(workers)) {
        eprintln!("fatal: {e}");
        std::process::exit(1);
    }
}
