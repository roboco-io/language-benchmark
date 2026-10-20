# RealWorld API — Kotlin + Spring Boot + SQLite

A backend for the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) spec. It covers JWT authentication, users and profiles, articles, comments, favorites, tags, and the feed.

The stack is Kotlin on Java 25, Spring Boot 4 (Web MVC), Spring Data JPA with Hibernate, and SQLite. Gradle (Kotlin DSL) builds the project with the installed `gradle` command, so the repository has no Gradle wrapper.

## Running

```sh
PORT=8000 ./start.sh
```

`start.sh` resolves dependencies, builds the executable jar, and runs the server in the foreground. The server listens on `PORT` (default `8080`).

The application reads these environment variables:

| Variable          | Default                  | Purpose                       |
|-------------------|--------------------------|-------------------------------|
| `PORT`            | `8080`                   | HTTP port                     |
| `DATABASE_PATH`   | `realworld.db`           | SQLite database file          |
| `JWT_SECRET`      | built-in dev secret      | HMAC key used to sign tokens  |
| `JWT_TTL_SECONDS` | `2592000` (30 days)      | Token lifetime                |

Set `JWT_SECRET` in any real deployment, because the built-in default is public.

## Docker

```sh
docker build -t realworld-kotlin .
docker run -e PORT=8000 -p 8000:8000 -v rw-data:/data realworld-kotlin
```

The image is built in two stages. The first stage uses Gradle to build the release jar. The second stage runs that jar on a JRE-only image. When the server starts with an empty database at `/data/realworld.db`, Hibernate creates the schema (`ddl-auto=update`).

## Runtime tuning

The embedded Tomcat worker pool (`maxThreads` and `minSpareThreads`) and the Hikari connection pool are both sized to `Runtime.availableProcessors()`. The startup log reports the chosen size.

SQLite runs with `journal_mode=WAL` and `busy_timeout=5000`. Transactions begin in `IMMEDIATE` mode, so two concurrent writers wait on the busy timeout instead of failing with a lock-upgrade deadlock. The startup log reports the effective `journal_mode` and `busy_timeout`.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests). It comes from `realworld-apps/realworld` at commit `ebbcdeb`. To run it against a running server:

```sh
HOST=http://localhost:8000 ./api/run-api-tests-hurl.sh
```
