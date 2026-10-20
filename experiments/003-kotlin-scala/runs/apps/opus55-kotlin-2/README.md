# RealWorld API — Kotlin + Spring Boot + SQLite

A backend implementation of the [RealWorld API spec](https://realworld-docs.netlify.app/specifications/backend/endpoints/):
JWT authentication, users and profiles, articles, comments, favorites, tags, and the feed.

Stack: Kotlin (JVM, Java 25), Spring Boot 3.5, Spring Data JPA (Hibernate), SQLite, Gradle (Kotlin DSL).

## Running

```sh
PORT=8080 ./start.sh
```

`start.sh` downloads dependencies, builds the release jar with the installed `gradle`, and runs the server
in the foreground.

With Docker:

```sh
docker build -t realworld-kotlin .
docker run -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-kotlin
```

The container creates its schema on first start when the database is empty.

## Configuration

| Variable          | Default                         | Meaning                          |
|-------------------|---------------------------------|----------------------------------|
| `PORT`            | `8080`                          | HTTP port                        |
| `DB_PATH`         | `./data/realworld.db` (`/data/realworld.db` in Docker) | SQLite database file |
| `JWT_SECRET`      | development value               | HMAC key for HS256 tokens; set it in production |
| `JWT_TTL_SECONDS` | `2592000` (30 days)             | Token lifetime                   |

## Runtime tuning

The embedded Tomcat worker pool and the Hikari connection pool are sized to the number of available CPU
cores (`Runtime.availableProcessors()`). Every SQLite connection uses `journal_mode=WAL` and
`busy_timeout=5000`. The effective values are logged at startup.

## API tests

The official RealWorld Hurl suite is vendored in `api/hurl` (from `realworld-apps/realworld`, `specs/api`).

```sh
HOST=http://localhost:8080 ./api/run-api-tests-hurl.sh
```
