import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Database from "better-sqlite3";
import { busyTimeoutMs } from "./config.js";

const migrationsDir = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "prisma",
  "migrations",
);

// Prepare the SQLite database before any worker starts: switch it to WAL mode
// (a persistent, file-level setting) and apply every migration under
// prisma/migrations that has not been applied yet. This lets the server start
// against an empty database without needing the Prisma CLI at runtime.
export function prepareDatabase(databasePath: string): void {
  fs.mkdirSync(path.dirname(databasePath), { recursive: true });
  const db = new Database(databasePath, { timeout: busyTimeoutMs });
  try {
    db.pragma(`busy_timeout = ${busyTimeoutMs}`);
    db.pragma("journal_mode = WAL");
    db.pragma("foreign_keys = ON");
    db.exec(
      `CREATE TABLE IF NOT EXISTS "_app_migrations" ("name" TEXT PRIMARY KEY, "appliedAt" TEXT NOT NULL)`,
    );
    const applied = new Set(
      db
        .prepare(`SELECT "name" FROM "_app_migrations"`)
        .all()
        .map((row) => (row as { name: string }).name),
    );
    const pending = fs
      .readdirSync(migrationsDir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && !applied.has(entry.name))
      .map((entry) => entry.name)
      .sort();
    for (const name of pending) {
      const sql = fs.readFileSync(path.join(migrationsDir, name, "migration.sql"), "utf8");
      db.transaction(() => {
        db.exec(sql);
        db.prepare(`INSERT INTO "_app_migrations" ("name", "appliedAt") VALUES (?, ?)`).run(
          name,
          new Date().toISOString(),
        );
      })();
      console.log(`Applied migration ${name}`);
    }
  } finally {
    db.close();
  }
}
