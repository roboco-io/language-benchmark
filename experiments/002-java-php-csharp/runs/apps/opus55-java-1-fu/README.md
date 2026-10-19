# RealWorld API — Java 25 / Spring Boot / JPA / SQLite

A backend for the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) spec:
JWT authentication, users and profiles, articles, comments, favorites, tags and the personal feed.

In addition to the spec, `PUT /api/articles/:slug/comments/:id` updates a comment's body. The request
body is `{"comment":{"body":"..."}}` and the response has the same shape as the create-comment response.
Only the comment author may update it (other users get `403`). The update keeps `id` and `createdAt` and
refreshes `updatedAt`.

## Stack

- Java 25, Spring Boot 3.5 (embedded Tomcat), Spring Data JPA with Hibernate 6
- SQLite through `sqlite-jdbc` and the Hibernate community `SQLiteDialect`
- Maven build

## Running

```sh
PORT=8080 ./start.sh
```

`start.sh` downloads the dependencies, builds `target/app.jar` and then runs the server in the foreground.
The server listens on the port in `PORT` (the default is 8080).

### Docker

```sh
docker build -t realworld-java .
docker run --rm -e PORT=8080 -p 8080:8080 -v realworld-data:/app/data realworld-java
```

The image uses a multi-stage build. The release jar is built in a Maven/JDK stage, then the extracted
Spring Boot layers run on a JRE-only image. When the database is empty at startup, Hibernate creates the
schema (`ddl-auto=update`).

## Configuration

| Variable          | Default                 | Purpose                                 |
|-------------------|-------------------------|-----------------------------------------|
| `PORT`            | `8080`                  | HTTP port                               |
| `DB_PATH`         | `data/realworld.db`     | SQLite database file (directory is created) |
| `JWT_SECRET`      | development value       | HMAC-SHA256 key for tokens; set it in production |
| `JWT_TTL_SECONDS` | `2592000` (30 days)     | Token lifetime                          |

## Concurrency and SQLite

At startup the application sets the Tomcat worker pool (`server.tomcat.threads.max`/`min-spare`) and
the Hikari JDBC pool to `Runtime.availableProcessors()`. Inside a container this value follows the CPU
limit. You can override these values with the normal Spring properties.

Every SQLite connection is opened with `journal_mode=WAL` and `busy_timeout=5000`. The effective values
are logged at startup. Read-write transactions start with `BEGIN IMMEDIATE`. They also pass through a
fair in-process lock, because the SQLite busy handler does not queue waiters in order. Read-only
transactions stay deferred and run concurrently under WAL.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl`. It comes from `realworld-apps/realworld`,
directory `specs/api/hurl`, and has 13 files with 154 requests. To run it against a running server:

```sh
HOST=http://localhost:8080 api/hurl/run-hurl-tests.sh
```

The test for the comment update endpoint is in `api/hurl-extra`, outside the vendored suite. To run all
tests together:

```sh
HOST=http://localhost:8080 api/hurl/run-hurl-tests.sh api/hurl/*.hurl api/hurl-extra/*.hurl
```
