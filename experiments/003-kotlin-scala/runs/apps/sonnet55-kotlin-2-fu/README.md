# RealWorld backend (Kotlin / Spring Boot / SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

- Kotlin on JVM 25, Spring Boot 3.5, Spring Data JPA (Hibernate), SQLite (WAL, `busy_timeout=5000`), JWT auth.
- Tomcat thread pool and DB pool are sized to the number of CPU cores.

## Run

    PORT=8080 ./start.sh        # builds and runs in the foreground

Environment: `PORT` (default 8080), `DB_PATH` (default `realworld.db`), `JWT_SECRET`.

## Test

The official Hurl tests are vendored in `hurl/`. With the server running:

    PORT=8080 ./run-tests.sh

## Docker

    docker build -t realworld .
    docker run -e PORT=8080 -p 8080:8080 realworld

## Extension: update comment

`PUT /api/articles/:slug/comments/:id` updates a comment body. Only the comment author may call it (403 otherwise). Covered by `hurl/comments_update.hurl`.
