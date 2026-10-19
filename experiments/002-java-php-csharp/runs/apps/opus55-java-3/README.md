# RealWorld API — Java 25 + Spring Boot + JPA + SQLite

A backend implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/)
API spec: JWT authentication, users and profiles, articles, comments, favorites, tags and the feed.

| Component   | Choice                                                     |
|-------------|------------------------------------------------------------|
| Language    | Java 25                                                    |
| Framework   | Spring Boot 3.5 (embedded Tomcat, Spring MVC)              |
| Persistence | Spring Data JPA / Hibernate 6.6, `SQLiteDialect`           |
| Database    | SQLite (`sqlite-jdbc`), WAL mode, `busy_timeout=5000` ms   |
| Build       | Maven                                                      |

## Running

```bash
PORT=8080 ./start.sh
```

`start.sh` resolves dependencies and builds the jar with Maven, then runs the server in the foreground.
The server listens on `$PORT` (default `8080`).

### Docker

```bash
docker build -t realworld-java .
docker run --rm -e PORT=8080 -p 8080:8080 -v realworld-data:/data realworld-java
```

The image is built from a release jar (tests skipped, Spring Boot layers extracted) and runs on a JRE base
image as a non-root user. The database lives at `/data/realworld.db`. On first start with an empty database,
Hibernate creates the schema (`spring.jpa.hibernate.ddl-auto=update`).

### Configuration

| Variable          | Default                  | Purpose                                 |
|-------------------|--------------------------|-----------------------------------------|
| `PORT`            | `8080`                   | HTTP port                               |
| `DATABASE_PATH`   | `realworld.db`           | SQLite database file                    |
| `JWT_SECRET`      | development value        | HMAC-SHA256 key for tokens; set it in production |
| `JWT_TTL_SECONDS` | `2592000` (30 days)      | Token lifetime                          |
| `JAVA_OPTS`       | empty (Docker: heap sizing) | Extra JVM options                    |

## Concurrency model

At startup, `RealWorldApplication` reads `Runtime.availableProcessors()` and uses that number as the default
for the Tomcat request thread pool (`server.tomcat.threads.max` and `min-spare`) and for the Hikari connection
pool size. Explicit configuration still overrides these defaults. A log line at startup reports the effective
values, for example:

```
cpu cores=10, tomcat threads=10, sqlite journal_mode=wal, busy_timeout=5000ms
```

SQLite allows only one writer at a time. When several write transactions race for the database lock, SQLite
returns `SQLITE_BUSY` immediately in some cases, for example when a read lock must be upgraded to a write lock,
and the 5000 ms `busy_timeout` does not help there. To prevent these errors, `SqliteTransactionManager`
serializes read-write transactions with a fair in-process lock that it takes before each transaction begins.
Read-only transactions do not take this lock and run in parallel, because WAL mode lets readers proceed while
a writer is active. The `busy_timeout` still covers contention with other processes and with WAL checkpoints.
Password hashing (bcrypt) runs before the write transaction starts, so it does not hold the write lock.

## Tests

The official RealWorld Hurl suite is vendored in `api/hurl` (13 files, 154 requests), copied from
[realworld-apps/realworld](https://github.com/realworld-apps/realworld) `specs/api` at commit
`ebbcdeb8d55b42a3a613c787560498b8ef10003f`. To run the suite against a running server
([Hurl](https://hurl.dev) must be installed):

```bash
HOST=http://localhost:8080 ./api/run-api-tests-hurl.sh
```

## Project layout

```
src/main/java/io/realworld/
  RealWorldApplication.java   entry point; sizes thread and connection pools to the core count
  common/                     errors, validation, JWT, auth header parsing, SQLite transaction manager
  user/                       users, authentication, profiles and follows
  article/                    articles, tags, favorites, feed and comments
```
