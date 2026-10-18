# RealWorld backend (Fastify + Prisma + SQLite)

A TypeScript implementation of the [RealWorld](https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API:
JWT authentication, users and profiles, articles, comments, favorites, tags and the personal feed.

## Run

```sh
PORT=3000 ./start.sh
```

`start.sh` installs dependencies, builds the project and starts the server in the foreground.
The port comes from `PORT` (default 3000). The database is `./data/app.db` unless `DATABASE_URL` is set.

### Docker

```sh
docker build -t realworld .
docker run -e PORT=3000 -p 3000:3000 -v realworld-data:/data realworld
```

The image uses a multi-stage build and ships only compiled output and production dependencies.
The server creates its schema when it starts on an empty database.

## Runtime design

- The primary process creates the schema (idempotent `CREATE ... IF NOT EXISTS` statements generated from
  `prisma/schema.prisma`), then forks one worker per available CPU core (`os.availableParallelism()`,
  override with `WORKERS`). Crashed workers are restarted.
- SQLite runs in WAL mode with `busy_timeout = 5000` ms. Each worker uses a single Prisma connection.
- After changing `prisma/schema.prisma`, run `node scripts/gen-schema.mjs` to regenerate `src/schema-sql.ts`.

Environment variables: `PORT`, `HOST`, `DATABASE_URL`, `JWT_SECRET`, `WORKERS`.

## Tests

The official RealWorld Hurl suite (13 files, 154 requests) is vendored in `tests/hurl`.
With the server running:

```sh
HOST=http://localhost:3000 bash tests/hurl/run-hurl-tests.sh
```
