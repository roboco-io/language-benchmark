# RealWorld backend (C# / .NET 10)

Implementation of the RealWorld backend API with ASP.NET Core minimal APIs, Entity Framework Core and SQLite.

## Run

    ./start.sh            # restores, builds (Release) and runs in the foreground; port from $PORT (default 8080)

Docker:

    docker build -t realworld-cs .
    docker run -e PORT=8080 -p 8080:8080 realworld-cs

The schema is created automatically on first start. Environment variables: `PORT`, `DB_PATH` (default `data/realworld.db`), `JWT_SECRET`.

## Tests

The official RealWorld Hurl tests are vendored in `tests/hurl` (13 files, 154 requests). With the server running:

    PORT=8080 tests/run-hurl.sh

## Notes

- Kestrel uses the default .NET thread pool, which scales with the number of CPU cores.
- Every SQLite connection sets `journal_mode=WAL` and `busy_timeout=5000`.
- JWT (HS256) is implemented in `src/RealWorld/Security.cs`; passwords use PBKDF2-SHA256.
