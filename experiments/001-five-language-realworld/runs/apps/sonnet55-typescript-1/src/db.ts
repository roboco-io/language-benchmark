import { PrismaClient } from '@prisma/client';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { SCHEMA_STATEMENTS } from './schema-sql';

const BUSY_TIMEOUT_MS = 5000;

/**
 * Resolves DATABASE_URL to an absolute `file:` URL (default: ./data/app.db under the cwd).
 * Prisma otherwise resolves relative paths against the schema directory, which is confusing.
 */
export function baseDatabaseUrl(): string {
  const raw = process.env.DATABASE_URL ?? 'file:./data/app.db';
  if (!raw.startsWith('file:')) return raw;
  const [path, query] = raw.slice('file:'.length).split('?');
  const abs = resolve(process.cwd(), path);
  mkdirSync(dirname(abs), { recursive: true });
  return `file:${abs}${query ? `?${query}` : ''}`;
}

function databaseUrl(): string {
  const base = baseDatabaseUrl();
  // One connection per worker process keeps SQLite writers serialized inside
  // a process, so the per-connection PRAGMAs below always apply.
  // socket_timeout makes the SQLite driver itself wait up to 5 s on a locked database.
  const sep = base.includes('?') ? '&' : '?';
  return `${base}${sep}connection_limit=1&socket_timeout=${BUSY_TIMEOUT_MS / 1000}`;
}

export function createClient(): PrismaClient {
  return new PrismaClient({ datasources: { db: { url: databaseUrl() } } });
}

/** Applies connection PRAGMAs: WAL journal and a 5000 ms busy timeout. */
export async function configureConnection(prisma: PrismaClient): Promise<void> {
  await prisma.$queryRawUnsafe('PRAGMA journal_mode = WAL');
  await prisma.$queryRawUnsafe(`PRAGMA busy_timeout = ${BUSY_TIMEOUT_MS}`);
  await prisma.$queryRawUnsafe('PRAGMA synchronous = NORMAL');
  await prisma.$queryRawUnsafe('PRAGMA foreign_keys = ON');
}

/** Creates all tables when the database is empty. Safe to run repeatedly. */
export async function ensureSchema(prisma: PrismaClient): Promise<void> {
  await configureConnection(prisma);
  for (const stmt of SCHEMA_STATEMENTS) {
    await prisma.$executeRawUnsafe(stmt);
  }
}
