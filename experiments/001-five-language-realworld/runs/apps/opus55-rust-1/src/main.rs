mod articles;
mod auth;
mod body;
mod comments;
mod error;
mod profiles;
mod users;

use std::{str::FromStr, sync::Arc, time::Duration};

use axum::{
    routing::{delete, get, post},
    Router,
};
use sqlx::{
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
    SqlitePool,
};
use tower_http::{cors::CorsLayer, trace::TraceLayer};

#[derive(Clone)]
pub struct AppState {
    pub pool: SqlitePool,
    pub jwt_secret: Arc<String>,
}

const SCHEMA: &str = include_str!("schema.sql");
const TIMESTAMP_FORMAT: &str = "%Y-%m-%dT%H:%M:%S%.3fZ";

/// Current UTC time as an ISO 8601 string with millisecond precision.
pub fn now_timestamp() -> String {
    chrono::Utc::now().format(TIMESTAMP_FORMAT).to_string()
}

/// Returns `timestamp` plus one millisecond.
pub fn bump_timestamp(timestamp: &str) -> String {
    chrono::DateTime::parse_from_rfc3339(timestamp)
        .map(|t| (t + chrono::Duration::milliseconds(1)).format(TIMESTAMP_FORMAT).to_string())
        .unwrap_or_else(|_| now_timestamp())
}

fn router(state: AppState) -> Router {
    let api = Router::new()
        .route("/users", post(users::register))
        .route("/users/login", post(users::login))
        .route("/user", get(users::current_user).put(users::update_user))
        .route("/profiles/{username}", get(profiles::get_profile))
        .route(
            "/profiles/{username}/follow",
            post(profiles::follow).delete(profiles::unfollow),
        )
        .route(
            "/articles",
            get(articles::list_articles).post(articles::create_article),
        )
        .route("/articles/feed", get(articles::feed))
        .route(
            "/articles/{slug}",
            get(articles::get_article)
                .put(articles::update_article)
                .delete(articles::delete_article),
        )
        .route(
            "/articles/{slug}/favorite",
            post(articles::favorite).delete(articles::unfavorite),
        )
        .route(
            "/articles/{slug}/comments",
            get(comments::list_comments).post(comments::add_comment),
        )
        .route(
            "/articles/{slug}/comments/{id}",
            delete(comments::delete_comment),
        )
        .route("/tags", get(articles::list_tags));

    Router::new()
        .nest("/api", api)
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn connect(database_url: &str, max_connections: u32) -> Result<SqlitePool, sqlx::Error> {
    let options = SqliteConnectOptions::from_str(database_url)?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .synchronous(SqliteSynchronous::Normal)
        .busy_timeout(Duration::from_millis(5000))
        .foreign_keys(true);
    let pool = SqlitePoolOptions::new()
        .max_connections(max_connections)
        .connect_with(options)
        .await?;
    sqlx::raw_sql(SCHEMA).execute(&pool).await?;
    Ok(pool)
}

async fn run(workers: usize) -> Result<(), Box<dyn std::error::Error>> {
    let database_url =
        std::env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite://realworld.db".to_string());
    let jwt_secret =
        std::env::var("JWT_SECRET").unwrap_or_else(|_| "realworld-development-secret".to_string());
    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8000);

    let pool = connect(&database_url, (workers as u32).max(4)).await?;
    let state = AppState {
        pool,
        jwt_secret: Arc::new(jwt_secret),
    };

    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await?;
    tracing::info!("listening on port {port} with {workers} worker threads");
    axum::serve(listener, router(state))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    // One tokio worker thread per available CPU core.
    let workers = std::thread::available_parallelism().map_or(1, |n| n.get());
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(workers)
        .enable_all()
        .build()?
        .block_on(run(workers))
}
