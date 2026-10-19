# RealWorld backend (Spring Boot + JPA + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
backend API: JWT authentication, users/profiles, articles, comments, favorites, tags and feed.

Stack: Java 25, Spring Boot 3.5, Spring Data JPA (Hibernate), SQLite, Maven.

## Run

```sh
PORT=8080 ./start.sh
```

`start.sh` builds with Maven (downloading dependencies on the first run) and starts the server in the foreground.

| Variable     | Default                        | Meaning                              |
|--------------|--------------------------------|--------------------------------------|
| `PORT`       | `8080`                         | HTTP port                            |
| `DB_PATH`    | `realworld.db` (`/data/...` in Docker) | SQLite database file; the schema is created automatically when it is empty |
| `JWT_SECRET` | built-in development secret    | HMAC key for JWT signing; set it in production |

## Docker

```sh
docker build -t realworld .
docker run -p 8080:8080 -e PORT=8080 realworld
```

The image is built from the release jar (`mvn package`) and creates its schema on an empty database.

## Tests

The official RealWorld Hurl tests are vendored in `api/hurl`. With the server running:

```sh
PORT=8080 ./run-tests.sh
```

## Runtime notes

- Tomcat's worker thread pool is sized to `Runtime.availableProcessors()`.
- SQLite runs in WAL mode with `busy_timeout=5000` ms (set through the JDBC URL).
- Passwords use PBKDF2-HMAC-SHA256; tokens are HS256 JWTs (`Authorization: Token <jwt>`).

## Extension: update a comment

`PUT /api/articles/:slug/comments/:id` with `{"comment":{"body":"..."}}` (auth required).
Returns `200` with the updated comment (`updatedAt` refreshed). Errors: `401` missing token,
`404` unknown article or comment, `403` caller is not the comment author, `422` blank body.
Covered by `api/hurl/comments_update.hurl`.
