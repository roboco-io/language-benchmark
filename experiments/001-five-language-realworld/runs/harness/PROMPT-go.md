
Implement the RealWorld App (https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API.

- Tech stack: Go + Gin + GORM + SQLite (pure-Go modernc.org/sqlite driver)
- Scope: authentication (JWT), users/profiles, article CRUD, comments, favorites, tags, feed — the full RealWorld backend spec
- Completion criteria:
  1. All official RealWorld Hurl API tests pass — tests: `api/hurl` in https://github.com/realworld-apps/realworld (13 files, 154 requests). Vendor them into the repo and run them.
  2. Running `start.sh` once at the repo root installs dependencies, builds, and starts the server. The server reads its port from the environment variable `PORT` and keeps running in the foreground.
  3. The `Dockerfile` at the repo root builds an image from a release (optimized) build. The container also reads its port from `PORT`, and it creates its own schema when it starts with an empty database.
  4. The server uses as many workers or threads as there are available CPU cores: GOMAXPROCS equal to the core count. SQLite uses WAL mode and a `busy_timeout` of 5000 ms.

Language directive (important):

- **All text you produce** in this project must be written in English: README and docs, code comments, git commit messages, and progress reports. (Code identifiers and library APIs excluded.)

Working instructions:

- First inspect the current repo state (files, git log), then pick the single most important remaining piece of work toward the completion criteria and do it.
- Make a git commit for each unit of work.
- Language toolchains are preinstalled on the host; do not install or upgrade compilers or language runtimes.
- Only after directly running and verifying all completion criteria, create a `.ralph-done` file at the repo root. Never create it without verification.
- Do not modify PROMPT.md.