import { PrismaClient } from '@prisma/client';
import fs from 'node:fs';
import path from 'node:path';
import { SCHEMA_STATEMENTS } from './schema';

const DEFAULT_DB_URL = 'file:./data/realworld.db';

// Prisma resolves relative sqlite paths against the schema directory, so resolve them against
// the working directory instead and create the parent directory when it is missing.
export function databaseUrl(): string {
  let base = process.env.DATABASE_URL || DEFAULT_DB_URL;
  const m = /^file:([^?]*)(\?.*)?$/.exec(base);
  if (m) {
    const file = path.resolve(process.cwd(), m[1]);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    base = `file:${file}${m[2] ?? ''}`;
  }
  // One connection per worker process: SQLite allows a single writer, so extra pooled
  // connections only add lock contention. socket_timeout is Prisma's busy_timeout (seconds).
  if (base.includes('connection_limit')) return base;
  return base + (base.includes('?') ? '&' : '?') + 'connection_limit=1&socket_timeout=5';
}

export function createPrisma(): PrismaClient {
  return new PrismaClient({ datasources: { db: { url: databaseUrl() } } });
}

// Applied on every connection (the pool has exactly one). journal_mode=WAL is persisted in the
// database file; busy_timeout is per-connection, so it is set explicitly here as well.
export async function applyPragmas(prisma: PrismaClient): Promise<void> {
  await prisma.$queryRawUnsafe('PRAGMA journal_mode = WAL');
  await prisma.$queryRawUnsafe('PRAGMA busy_timeout = 5000');
  await prisma.$queryRawUnsafe('PRAGMA synchronous = NORMAL');
  await prisma.$queryRawUnsafe('PRAGMA foreign_keys = ON');
}

export async function ensureSchema(prisma: PrismaClient): Promise<void> {
  await applyPragmas(prisma);
  for (const stmt of SCHEMA_STATEMENTS) await prisma.$executeRawUnsafe(stmt);
}
