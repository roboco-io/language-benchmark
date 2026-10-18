mod articles;
mod auth;
mod comments;
mod db;
mod error;
mod profiles;
mod users;

use std::{net::SocketAddr, sync::Arc, thread::available_parallelism};

use axum::{
    routing::{get, post},
    Router,
};
use sqlx::SqlitePool;
use tower_http::cors::CorsLayer;

#[derive(Clone)]
pub struct AppState {
    pub pool: SqlitePool,
    pub jwt_secret: Arc<str>,
}

fn router(state: AppState) -> Router {
    Router::new()
        .route("/api/users", post(users::register))
        .route("/api/users/login", post(users::login))
        .route("/api/user", get(users::current_user).put(users::update_user))
        .route("/api/profiles/{username}", get(profiles::get_profile))
        .route("/api/profiles/{username}/follow", post(profiles::follow).delete(profiles::unfollow))
        .route("/api/articles", get(articles::list_articles).post(articles::create_article))
        .route("/api/articles/feed", get(articles::feed))
        .route(
            "/api/articles/{slug}",
            get(articles::get_article).put(articles::update_article).delete(articles::delete_article),
        )
        .route("/api/articles/{slug}/favorite", post(articles::favorite).delete(articles::unfavorite))
        .route("/api/articles/{slug}/comments", get(comments::list_comments).post(comments::add_comment))
        .route("/api/articles/{slug}/comments/{id}", axum::routing::put(comments::update_comment).delete(comments::delete_comment))
        .route("/api/tags", get(articles::list_tags))
        .layer(CorsLayer::permissive())
        .with_state(state)
}

async fn serve(workers: usize) -> Result<(), Box<dyn std::error::Error>> {
    let port: u16 = std::env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8000);
    let database_url = std::env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite://realworld.db".to_string());
    let jwt_secret = std::env::var("JWT_SECRET").unwrap_or_else(|_| "realworld-dev-secret-change-me".to_string());

    let pool = db::connect(&database_url, (workers as u32 * 2).max(4)).await?;
    let app = router(AppState { pool, jwt_secret: jwt_secret.into() });

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = tokio::net::TcpListener::bind(addr).await?;
    println!("RealWorld API listening on http://{addr} ({workers} worker threads, db: {database_url})");
    axum::serve(listener, app).with_graceful_shutdown(shutdown_signal()).await?;
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut sig) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            sig.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // One tokio worker thread per available CPU core.
    let workers = available_parallelism().map(|n| n.get()).unwrap_or(1);
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(workers)
        .enable_all()
        .build()?
        .block_on(serve(workers))
}
