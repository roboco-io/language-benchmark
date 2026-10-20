# RealWorld API — Kotlin + Spring Boot + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) API spec
(JWT authentication, users, profiles, articles, comments, favorites, tags, feed).

Beyond the spec, it also supports updating a comment: `PUT /api/articles/:slug/comments/:id` with
`{"comment":{"body":"..."}}`. Only the comment author may update it (`403` otherwise).

Stack: Kotlin on Java 25, Spring Boot 3.5 (embedded Tomcat), Spring Data JPA / Hibernate, SQLite, Gradle Kotlin DSL.

## Running

```sh
PORT=8080 ./start.sh
```

`start.sh` resolves dependencies, builds the executable jar with the installed `gradle`, and runs the server in the foreground.

With Docker:

```sh
docker build -t realworld-kotlin .
docker run -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-kotlin
```

## Configuration

| Variable          | Default                  | Meaning                                    |
|-------------------|--------------------------|--------------------------------------------|
| `PORT`            | `8080`                   | HTTP port                                  |
| `DATABASE_PATH`   | `realworld.db` (`/data/realworld.db` in Docker) | SQLite database file |
| `JWT_SECRET`      | development value        | HMAC key used to sign tokens; set it in production |
| `JWT_TTL_SECONDS` | `2592000` (30 days)      | Token lifetime                             |

The schema (`src/main/resources/schema.sql`) is applied on every startup with `CREATE ... IF NOT EXISTS`,
so an empty database is initialized automatically. Every SQLite connection uses WAL journal mode and a
`busy_timeout` of 5000 ms. The embedded Tomcat worker pool is sized to the number of available CPU cores.

## API tests

The official Hurl tests are vendored under `api/hurl`, together with `comments_update.hurl`,
which covers the comment update endpoint. With the server running:

```sh
HOST=http://localhost:8080 ./run-api-tests.sh
```
