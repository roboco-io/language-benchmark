# RealWorld backend (Kotlin + Spring Boot + SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

- Kotlin on JVM 25, Spring Boot (embedded Tomcat), Spring Data JPA (Hibernate), SQLite (WAL, `busy_timeout=5000`)
- JWT (HS256) authentication using the `Authorization: Token <jwt>` header
- Tomcat worker threads and the DB pool are sized to the number of available CPU cores

## Run

    ./start.sh            # builds and runs in the foreground; port from $PORT (default 8080)

Environment variables: `PORT`, `DB_PATH` (SQLite file, default `realworld.db`), `JWT_SECRET`.

## Docker

    docker build -t realworld .
    docker run -p 8080:8080 -e PORT=8080 realworld

The schema is created automatically on startup when the database is empty.

## Tests

The official Hurl tests are vendored in `api/hurl`. With the server running:

    HOST=http://localhost:8080 bash api/hurl/run-hurl-tests.sh
