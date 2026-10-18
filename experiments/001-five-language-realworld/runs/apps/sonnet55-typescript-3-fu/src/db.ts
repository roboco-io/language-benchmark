import { PrismaClient } from '@prisma/client';
import { SCHEMA_STATEMENTS } from './schema';

// SQLite allows a single writer, and the PRAGMAs below are per-connection, so each worker process
// keeps exactly one pooled connection. Parallelism comes from the cluster, not from the pool.
function databaseUrl(): string {
  const base = process.env.DATABASE_URL ?? 'file:./dev.db';
  if (base.includes('connection_limit')) return base;
  return base + (base.includes('?') ? '&' : '?') + 'connection_limit=1&socket_timeout=5';
}

export const prisma = new PrismaClient({ datasources: { db: { url: databaseUrl() } } });

// PRAGMA statements return rows, so they must go through $queryRawUnsafe.
export async function configureConnection(): Promise<void> {
  await prisma.$queryRawUnsafe('PRAGMA journal_mode = WAL');
  await prisma.$queryRawUnsafe('PRAGMA busy_timeout = 5000');
  await prisma.$queryRawUnsafe('PRAGMA synchronous = NORMAL');
  await prisma.$queryRawUnsafe('PRAGMA foreign_keys = ON');
}

export async function initSchema(): Promise<void> {
  await configureConnection();
  for (const stmt of SCHEMA_STATEMENTS) {
    await prisma.$executeRawUnsafe(stmt);
  }
}
