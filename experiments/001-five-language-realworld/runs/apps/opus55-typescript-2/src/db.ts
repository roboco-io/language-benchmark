import fs from 'node:fs';
import path from 'node:path';
import { Prisma, PrismaClient } from '@prisma/client';

export const BUSY_TIMEOUT_MS = 5000;

/** Resolves the SQLite connection URL; defaults to ./data/realworld.db under the working directory. */
export function databaseUrl(): string {
  const fromEnv = process.env.DATABASE_URL;
  if (fromEnv) return fromEnv;
  const file = path.resolve(process.cwd(), 'data', 'realworld.db');
  return `file:${file}`;
}

function ensureDatabaseDirectory(url: string): void {
  if (!url.startsWith('file:')) return;
  const file = url.slice('file:'.length).split('?')[0];
  fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
}

/**
 * Creates a Prisma client bound to a single SQLite connection so that per-connection
 * PRAGMAs (busy_timeout) apply to every query issued by this process.
 */
export async function createClient(): Promise<PrismaClient> {
  const base = databaseUrl();
  ensureDatabaseDirectory(base);
  const separator = base.includes('?') ? '&' : '?';
  const url = `${base}${separator}connection_limit=1&socket_timeout=${BUSY_TIMEOUT_MS / 1000}`;
  const prisma = new PrismaClient({ datasources: { db: { url } } });
  await prisma.$connect();
  await prisma.$queryRawUnsafe(`PRAGMA busy_timeout = ${BUSY_TIMEOUT_MS}`);
  await prisma.$queryRawUnsafe('PRAGMA journal_mode = WAL');
  await prisma.$queryRawUnsafe('PRAGMA synchronous = NORMAL');
  await prisma.$queryRawUnsafe('PRAGMA foreign_keys = ON');
  return prisma;
}

/** Applies the bundled migration SQL when the database has no schema yet. */
export async function ensureSchema(prisma: PrismaClient): Promise<boolean> {
  const rows = await prisma.$queryRaw<{ name: string }[]>(
    Prisma.sql`SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'User'`,
  );
  if (rows.length > 0) return false;

  const migrationsDir = path.resolve(__dirname, '..', 'prisma', 'migrations');
  const migrations = fs
    .readdirSync(migrationsDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
  for (const name of migrations) {
    const sql = fs.readFileSync(path.join(migrationsDir, name, 'migration.sql'), 'utf8');
    const statements = sql
      .split(/;\s*(?:\r?\n|$)/)
      .map((statement) => statement.replace(/^\s*--.*$/gm, '').trim())
      .filter((statement) => statement.length > 0);
    await prisma.$transaction(statements.map((statement) => prisma.$executeRawUnsafe(statement)));
  }
  return true;
}
