# RealWorld backend (Kotlin / Spring Boot / SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

- Kotlin on JVM 25, Spring Boot 3.5 (embedded Tomcat), Spring Data JPA (Hibernate), SQLite, Gradle Kotlin DSL.
- JWT (HS256) authentication with `Authorization: Token <jwt>`.
- Tomcat thread pool size equals the number of available CPU cores.
- SQLite runs in WAL mode with `busy_timeout=5000`. The schema is created on startup (`ddl-auto=update`).

## Run

```
PORT=8080 ./start.sh        # builds and starts the server in the foreground
```

Environment variables: `PORT` (default 8080), `DB_PATH` (default `data/realworld.db`), `JWT_SECRET`.

## Docker

```
docker build -t realworld .
docker run -p 8080:8080 -e PORT=8080 realworld
```

## Tests

The official Hurl tests are vendored in `api/hurl`. With the server running: `PORT=8080 ./run-tests.sh`.
