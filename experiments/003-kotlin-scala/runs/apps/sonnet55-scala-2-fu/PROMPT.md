The current repo already contains a working RealWorld (https://realworld-docs.netlify.app/specifications/backend/endpoints/) backend API. Add one new endpoint to it: updating a comment.

Endpoint: `PUT /api/articles/:slug/comments/:id`

- Request body: `{"comment":{"body":"<new text>"}}`. Authentication is required (`Authorization: Token <jwt>`).
- Success: respond `200` with `{"comment":{...}}` in the same shape as the existing create-comment response (`id`, `createdAt`, `updatedAt`, `body`, `author`). Only `body` changes, `id` and `createdAt` stay the same, and `updatedAt` is refreshed. A later `GET /api/articles/:slug/comments` must return the new body.
- Permission: only the comment author may update the comment. Any other authenticated user gets `403` with `{"errors":{"comment":["forbidden"]}}`.
- Missing token: `401` with `{"errors":{"token":["is missing"]}}`.
- Empty body: `422` with `{"errors":{"body":["can't be blank"]}}`.
- Unknown comment id on an existing article: `404` with `{"errors":{"comment":["not found"]}}`.
- Unknown article slug: `404` with `{"errors":{"article":["not found"]}}`.
- All error responses use the format `{"errors":{"<field>":["<message>"]}}`, the same as the existing endpoints.

Constraints:

- Do not break existing behavior. The existing RealWorld Hurl API tests (13 files, 154 requests) must still pass. `start.sh` (installs, builds, and starts the server on `PORT` in the foreground) and the root `Dockerfile` must keep working as before. Do not change the stack, the worker setup, or the SQLite settings.
- Write your own new Hurl test for the endpoint in the repo and run it together with the existing tests to verify your work.

Language directive (important):

- **All text you produce** in this project must be written in English: README and docs, code comments, git commit messages, and progress reports. (Code identifiers and library APIs excluded.)

Working instructions:

- First inspect the current repo state (files, git log), then do the most important remaining piece of work.
- Make a git commit for each unit of work.
- Language toolchains are preinstalled on the host; do not install or upgrade compilers or language runtimes.
- Only after directly running and verifying that the new endpoint works and all existing tests still pass, create a `.ralph-done` file at the repo root. Never create it without verification.
- Do not modify PROMPT.md.
