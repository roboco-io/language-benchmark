# RealWorld backend (Java 25, Spring Boot, JPA, SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API:
JWT authentication, users/profiles, articles, comments, favorites, tags, and feed.

## Run

```sh
PORT=8080 ./start.sh        # resolves dependencies, builds the jar, runs in the foreground
```

Docker:

```sh
docker build -t realworld .
docker run -p 8080:8080 -e PORT=8080 -v realworld-data:/data realworld
```

## Configuration (environment variables)

| Variable     | Default               | Purpose                                      |
|--------------|-----------------------|----------------------------------------------|
| `PORT`       | `8080`                | HTTP port                                    |
| `DB_PATH`    | `./data/realworld.db` | SQLite file (`/data/realworld.db` in Docker) |
| `JWT_SECRET` | dev value             | HS256 signing secret; set it in production   |

The schema is created by Hibernate at startup when the database is empty.
Tomcat's thread pool and the Hikari pool are sized to the CPU core count.
SQLite runs in WAL mode with `busy_timeout=5000` and `IMMEDIATE` transactions.

## Tests

The official RealWorld Hurl suite is vendored in `tests/hurl` (13 files, 154 requests).
With a server running:

```sh
PORT=8080 tests/run.sh
```
