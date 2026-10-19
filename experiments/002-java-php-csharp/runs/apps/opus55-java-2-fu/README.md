# RealWorld API — Java 25 + Spring Boot + JPA + SQLite

A backend implementation of the [RealWorld API spec](https://realworld-docs.netlify.app/specifications/backend/endpoints/):
JWT authentication, users and profiles, follows, article CRUD, favorites, comments, tags and the personal feed.
In addition to the spec, comments can be edited by their author via `PUT /api/articles/:slug/comments/:id`
with `{"comment":{"body":"..."}}` (200 with the updated comment; 401/403/404/422 errors in the usual format).

## Stack

Java 25, Spring Boot 3.5 (embedded Tomcat, Spring MVC), Spring Data JPA with Hibernate 6
(`hibernate-community-dialects` SQLite dialect), `sqlite-jdbc`, and Maven. Passwords are hashed with BCrypt
(`spring-security-crypto`); JWTs are HS256 tokens signed with the JDK `Mac` API.

## Running

```bash
PORT=8080 ./start.sh
```

`start.sh` resolves dependencies, builds `target/app.jar`, and runs the server in the foreground.
The schema is created automatically by Hibernate (`ddl-auto=update`) when the database is empty.

| Variable          | Default            | Meaning                         |
|-------------------|--------------------|---------------------------------|
| `PORT`            | `8080`             | HTTP port                       |
| `DB_PATH`         | `realworld.db`     | SQLite database file            |
| `JWT_SECRET`      | development value  | HMAC secret for signing tokens; set it in production |
| `JWT_TTL_SECONDS` | `2592000` (30 days) | Token lifetime                 |

### Docker

```bash
docker build -t realworld-java .
docker run --rm -e PORT=8080 -p 8080:8080 realworld-java
```

The image is a two-stage build: Maven builds the release jar, and the runtime stage contains only a JRE.
The database lives in `/app/data` (declared as a volume).

## Runtime tuning

The embedded Tomcat worker pool is sized to `Runtime.availableProcessors()` (both max and min-spare threads),
see `src/main/java/io/realworld/api/config/ServerConfig.java`. Every SQLite connection is opened with
`journal_mode=WAL` and `busy_timeout=5000` through JDBC URL parameters; the effective values are logged at
startup (`SQLite journal_mode=wal busy_timeout=5000ms`).

## API tests

The official Hurl suite (13 files, 154 requests) is vendored in `api/hurl` from
[realworld-apps/realworld](https://github.com/realworld-apps/realworld) (`specs/api/hurl`, commit `ebbcdeb8`).
`api/hurl/comments_update.hurl` is a project-specific addition covering the comment update endpoint; the runner
picks it up together with the official files (14 files, 167 requests).
With the server running:

```bash
HOST=http://localhost:8080 api/hurl/run-hurl-tests.sh
```
