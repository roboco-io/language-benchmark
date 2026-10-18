import os from "node:os";
import path from "node:path";

export const port = Number.parseInt(process.env.PORT ?? "3000", 10);
export const host = process.env.HOST ?? "0.0.0.0";

// Resolve DATABASE_URL ("file:<path>") to an absolute file path so the
// primary process and every worker open exactly the same SQLite file.
function resolveDatabasePath(): string {
  const url = process.env.DATABASE_URL ?? "file:./data/realworld.db";
  const file = url.startsWith("file:") ? url.slice("file:".length) : url;
  return path.resolve(process.cwd(), file);
}

export const databasePath = resolveDatabasePath();

export const workerCount = Number.parseInt(
  process.env.WEB_CONCURRENCY ?? String(os.availableParallelism()),
  10,
);

export const busyTimeoutMs = 5000;
