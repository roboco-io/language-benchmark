mod articles;
mod comments;
mod profiles;
mod tags;
mod users;

use axum::Router;
use axum::routing::{delete, get, post};
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::AppState;
use crate::error::ApiError;

pub fn router(state: AppState) -> Router {
    let api = Router::new()
        .route("/users", post(users::register))
        .route("/users/login", post(users::login))
        .route("/user", get(users::current).put(users::update))
        .route("/profiles/{username}", get(profiles::get_profile))
        .route(
            "/profiles/{username}/follow",
            post(profiles::follow).delete(profiles::unfollow),
        )
        .route("/articles", get(articles::list).post(articles::create))
        .route("/articles/feed", get(articles::feed))
        .route(
            "/articles/{slug}",
            get(articles::get_one)
                .put(articles::update)
                .delete(articles::remove),
        )
        .route(
            "/articles/{slug}/favorite",
            post(articles::favorite).delete(articles::unfavorite),
        )
        .route(
            "/articles/{slug}/comments",
            get(comments::list).post(comments::create),
        )
        .route("/articles/{slug}/comments/{id}", delete(comments::remove))
        .route("/tags", get(tags::list));

    Router::new()
        .nest("/api", api)
        .fallback(|| async { ApiError::not_found("route") })
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
        .with_state(state)
}
