use std::path::Path;
use std::str::FromStr;
use std::time::Duration;

use sqlx::SqlitePool;
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous};

const SCHEMA: &str = include_str!("schema.sql");

/// Opens the SQLite pool (WAL mode, 5000 ms busy timeout) and creates the
/// schema if the database is empty.
pub async fn connect(url: &str, max_connections: u32) -> Result<SqlitePool, sqlx::Error> {
    let options = SqliteConnectOptions::from_str(url)?
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .busy_timeout(Duration::from_millis(5000))
        .synchronous(SqliteSynchronous::Normal)
        .foreign_keys(true);

    // Make sure the parent directory of a file database exists.
    let filename = options.get_filename();
    if let Some(parent) = Path::new(filename).parent() {
        if !parent.as_os_str().is_empty() {
            let _ = std::fs::create_dir_all(parent);
        }
    }

    let pool = SqlitePoolOptions::new()
        .max_connections(max_connections.max(2))
        .connect_with(options)
        .await?;

    sqlx::raw_sql(SCHEMA).execute(&pool).await?;

    let journal: String = sqlx::query_scalar("PRAGMA journal_mode")
        .fetch_one(&pool)
        .await?;
    let busy: i64 = sqlx::query_scalar("PRAGMA busy_timeout")
        .fetch_one(&pool)
        .await?;
    tracing::info!("sqlite journal_mode={journal} busy_timeout={busy}ms");
    Ok(pool)
}

/// Current UTC time as an ISO 8601 string with millisecond precision.
pub fn now() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

/// Returns true when the error is a UNIQUE constraint violation.
pub fn is_unique_violation(err: &sqlx::Error) -> bool {
    matches!(err, sqlx::Error::Database(e) if e.is_unique_violation())
}
