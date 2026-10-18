import cluster from 'node:cluster';
import crypto from 'node:crypto';
import os from 'node:os';
import { buildApp } from './app';
import { createClient, ensureSchema } from './db';

const port = Number(process.env.PORT ?? 8000);
const host = process.env.HOST ?? '0.0.0.0';

function workerCount(): number {
  const fromEnv = Number(process.env.WEB_CONCURRENCY);
  if (Number.isInteger(fromEnv) && fromEnv > 0) return fromEnv;
  return os.availableParallelism();
}

async function runPrimary(): Promise<void> {
  if (!process.env.JWT_SECRET) {
    // Workers inherit the environment, so all of them share this secret.
    process.env.JWT_SECRET = crypto.randomBytes(32).toString('hex');
    console.warn('JWT_SECRET is not set; generated a random secret (tokens will not survive a restart).');
  }

  // Prepare the database once, before any worker starts serving requests.
  const prisma = await createClient();
  if (await ensureSchema(prisma)) console.log('Created database schema.');
  await prisma.$disconnect();

  const count = workerCount();
  console.log(`Primary ${process.pid} starting ${count} workers on port ${port}`);
  let shuttingDown = false;
  for (let i = 0; i < count; i++) cluster.fork();
  cluster.on('exit', (worker, code, signal) => {
    if (shuttingDown) return;
    console.error(`Worker ${worker.process.pid} exited (${signal ?? code}); restarting.`);
    cluster.fork();
  });

  const shutdown = () => {
    shuttingDown = true;
    for (const worker of Object.values(cluster.workers ?? {})) worker?.kill('SIGTERM');
    setTimeout(() => process.exit(0), 5000).unref();
    cluster.on('exit', () => {
      if (Object.keys(cluster.workers ?? {}).length === 0) process.exit(0);
    });
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

async function runWorker(): Promise<void> {
  const prisma = await createClient();
  const app = buildApp(prisma);
  await app.listen({ port, host });

  const shutdown = async () => {
    await app.close();
    await prisma.$disconnect();
    process.exit(0);
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

(cluster.isPrimary ? runPrimary() : runWorker()).catch((error) => {
  console.error(error);
  process.exit(1);
});
