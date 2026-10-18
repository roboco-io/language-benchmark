import { PrismaClient } from '@prisma/client';
import { readFileSync } from 'fs';
import { mkdirSync } from 'fs';
import path from 'path';

// Prisma resolves relative SQLite paths against the schema directory, so we
// normalise to an absolute path based on the working directory instead.
function resolveUrl(): string {
  const raw = process.env.DATABASE_URL || 'file:./data/app.db';
  if (!raw.startsWith('file:')) return raw;
  const [filePart, query] = raw.slice(5).split('?');
  const abs = path.resolve(process.cwd(), filePart);
  mkdirSync(path.dirname(abs), { recursive: true });
  return 'file:' + abs + (query ? '?' + query : '');
}

const baseUrl = resolveUrl();

// One pooled connection per process so the PRAGMAs set below apply to every query.
function withPoolLimit(url: string): string {
  if (/[?&]connection_limit=/.test(url)) return url;
  return url + (url.includes('?') ? '&' : '?') + 'connection_limit=1';
}

export const prisma = new PrismaClient({ datasourceUrl: withPoolLimit(baseUrl) });

/**
 * Applies per-connection SQLite settings. The connection pool is limited to one
 * connection per process (see withPoolLimit), so these
 * pragmas apply to every query this process runs.
 */
export async function configureConnection(): Promise<void> {
  await prisma.$queryRawUnsafe('PRAGMA busy_timeout = 5000');
  await prisma.$queryRawUnsafe('PRAGMA synchronous = NORMAL');
  await prisma.$queryRawUnsafe('PRAGMA foreign_keys = ON');
}

/**
 * Enables WAL (persistent in the database file) and creates the schema when
 * the database is empty. Safe to run repeatedly; the SQL is made idempotent.
 */
export async function initSchema(): Promise<void> {
  await configureConnection();
  await prisma.$queryRawUnsafe('PRAGMA journal_mode = WAL');
  const sqlPath = path.join(__dirname, '..', 'prisma', 'schema.sql');
  const sql = readFileSync(sqlPath, 'utf8')
    .replace(/CREATE TABLE /g, 'CREATE TABLE IF NOT EXISTS ')
    .replace(/CREATE UNIQUE INDEX /g, 'CREATE UNIQUE INDEX IF NOT EXISTS ')
    .replace(/CREATE INDEX /g, 'CREATE INDEX IF NOT EXISTS ');
  const statements = sql
    .split(';')
    .map((s) => s.replace(/^\s*--.*$/gm, '').trim())
    .filter(Boolean);
  for (const stmt of statements) {
    await prisma.$executeRawUnsafe(stmt);
  }
}
