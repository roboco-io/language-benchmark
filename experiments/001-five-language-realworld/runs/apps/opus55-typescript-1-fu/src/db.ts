import { PrismaBetterSqlite3 } from "@prisma/adapter-better-sqlite3";
import { PrismaClient } from "./generated/prisma/client.js";
import { busyTimeoutMs, databasePath } from "./config.js";

const adapter = new PrismaBetterSqlite3({ url: `file:${databasePath}`, timeout: busyTimeoutMs });

export const prisma = new PrismaClient({ adapter });

// Apply per-connection SQLite settings. WAL mode is already persisted in the
// database file by the primary process; setting it again is a cheap no-op.
export async function configureConnection(): Promise<void> {
  await prisma.$queryRawUnsafe("PRAGMA journal_mode = WAL");
  await prisma.$queryRawUnsafe(`PRAGMA busy_timeout = ${busyTimeoutMs}`);
  await prisma.$queryRawUnsafe("PRAGMA synchronous = NORMAL");
  await prisma.$queryRawUnsafe("PRAGMA foreign_keys = ON");
}
