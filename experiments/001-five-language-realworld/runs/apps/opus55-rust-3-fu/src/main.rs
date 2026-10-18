mod auth;
mod db;
mod error;
mod extract;
mod routes;

use std::net::SocketAddr;
use std::sync::Arc;

use sqlx::SqlitePool;

/// Shared application state handed to every handler.
#[derive(Clone)]
pub struct AppState {
    pub pool: SqlitePool,
    pub jwt: Arc<auth::JwtKeys>,
}

fn main() {
    // One tokio worker thread per available CPU core.
    let workers = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);

    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(workers)
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
        .block_on(run(workers));
}

async fn run(workers: usize) {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    let database_url =
        std::env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite:data/realworld.db".to_string());
    let pool = db::connect(&database_url, workers as u32)
        .await
        .expect("failed to open database");

    let secret = std::env::var("JWT_SECRET").unwrap_or_else(|_| {
        tracing::warn!("JWT_SECRET is not set; using an insecure development secret");
        "realworld-development-secret".to_string()
    });

    let state = AppState {
        pool,
        jwt: Arc::new(auth::JwtKeys::new(secret.as_bytes())),
    };

    let app = routes::router(state);

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8080);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .expect("failed to bind port");
    tracing::info!("listening on {addr} with {workers} worker threads");

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("server error");
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut s) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            s.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
