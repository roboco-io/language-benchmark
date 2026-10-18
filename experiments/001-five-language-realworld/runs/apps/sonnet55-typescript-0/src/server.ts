import cluster from 'node:cluster';
import os from 'node:os';
import { buildApp } from './app';
import { createPrisma, ensureSchema } from './db';

const port = Number(process.env.PORT) || 3000;
const host = process.env.HOST || '0.0.0.0';

async function runWorker(): Promise<void> {
  const prisma = createPrisma();
  await ensureSchema(prisma); // idempotent; also sets WAL + busy_timeout on this worker's connection
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

async function runPrimary(): Promise<void> {
  // Create the schema once before any worker starts so workers never race on DDL.
  const prisma = createPrisma();
  await ensureSchema(prisma);
  await prisma.$disconnect();

  const workers = Number(process.env.WORKERS) || os.availableParallelism();
  console.log(`primary ${process.pid}: starting ${workers} workers on port ${port}`);
  for (let i = 0; i < workers; i++) cluster.fork();

  let stopping = false;
  cluster.on('exit', (worker, code, signal) => {
    if (stopping) return;
    console.error(`worker ${worker.process.pid} exited (${signal ?? code}); restarting`);
    cluster.fork();
  });
  const stop = () => {
    stopping = true;
    for (const w of Object.values(cluster.workers ?? {})) w?.process.kill('SIGTERM');
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}

(cluster.isPrimary ? runPrimary() : runWorker()).catch((err) => {
  console.error(err);
  process.exit(1);
});
