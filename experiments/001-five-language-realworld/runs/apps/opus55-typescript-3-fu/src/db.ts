import { PrismaClient } from '@prisma/client';

// Each worker process keeps a single SQLite connection. SQLite allows only one
// writer at a time, so a larger pool inside one process adds lock contention
// without adding throughput. Concurrency comes from the cluster workers.
function withConnectionLimit(url: string): string {
  if (/[?&]connection_limit=/.test(url)) return url;
  return url + (url.includes('?') ? '&' : '?') + 'connection_limit=1';
}

const databaseUrl = withConnectionLimit(process.env.DATABASE_URL ?? 'file:./data/realworld.db');

export const prisma = new PrismaClient({ datasources: { db: { url: databaseUrl } } });

// Applies the required SQLite pragmas: a 5000 ms busy timeout so writers wait
// for the lock instead of failing with SQLITE_BUSY, and WAL journaling so
// readers in all worker processes do not block the writer. The busy timeout is
// set first because switching to WAL needs a brief exclusive lock.
// WAL mode is persistent in the database file; the primary process enables it
// once before it forks workers, and each worker confirms it.
export async function configureDatabase(): Promise<void> {
  await prisma.$queryRawUnsafe('PRAGMA busy_timeout = 5000;');
  const rows = await prisma.$queryRawUnsafe<{ journal_mode: string }[]>('PRAGMA journal_mode = WAL;');
  if (rows[0]?.journal_mode?.toLowerCase() !== 'wal') {
    throw new Error(`Could not enable WAL mode (journal_mode=${rows[0]?.journal_mode})`);
  }
  await prisma.$queryRawUnsafe('PRAGMA foreign_keys = ON;');
}
