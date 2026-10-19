# RealWorld backend (Java 25, Spring Boot, JPA, SQLite)

Implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

## Stack
- Java 25, Spring Boot 3.5, Spring Data JPA (Hibernate), SQLite (`sqlite-jdbc`), Maven
- JWT (HS256) and PBKDF2 password hashing implemented with JDK crypto only
- Tomcat thread pool sized to the number of available CPU cores
- SQLite runs in WAL mode with `busy_timeout=5000`; write transactions are serialized by an in-process lock

## Run
```sh
PORT=8080 ./start.sh        # installs dependencies, builds, runs in the foreground
```
Environment variables: `PORT` (default 8080), `DB_PATH` (default `realworld.db`), `JWT_SECRET`.
The schema is created automatically on startup when the database is empty.

## Docker
```sh
docker build -t realworld .
docker run -e PORT=8080 -p 8080:8080 realworld
```

## Tests
The official RealWorld Hurl tests are vendored in `hurl/` (13 files, 154 requests).
With the server running:
```sh
HOST=http://localhost:8080 hurl/run.sh
```
