# RealWorld backend (C# / ASP.NET Core / EF Core / SQLite)

Implementation of the RealWorld backend API spec on .NET 10.

## Run

```sh
PORT=8000 ./start.sh      # restore, Release build, run in the foreground
```

Environment variables: `PORT` (default 8000), `DB_PATH` (SQLite file, default `./realworld.db`), `JWT_SECRET`.

The schema is created automatically on startup when the database is empty.
SQLite uses WAL mode with `busy_timeout=5000`. Kestrel uses the default .NET thread pool, which scales with the CPU core count.

## Docker

```sh
docker build -t realworld .
docker run -p 8000:8000 -e PORT=8000 realworld
```

## Tests

The official Hurl API tests are vendored in `api/hurl` (13 files, 154 requests).

```sh
HOST=http://localhost:8000 api/hurl/run-hurl-tests.sh
```
