import cluster from 'node:cluster';
import { availableParallelism } from 'node:os';
import { buildApp } from './app';
import { configureConnection, createClient, ensureSchema } from './db';

const port = Number(process.env.PORT ?? 3000);
const host = process.env.HOST ?? '0.0.0.0';

async function runPrimary() {
  const prisma = createClient();
  await ensureSchema(prisma);
  await prisma.$disconnect();

  const workers = Number(process.env.WORKERS) || availableParallelism();
  console.log(`primary ${process.pid}: starting ${workers} workers on port ${port}`);
  for (let i = 0; i < workers; i++) cluster.fork();

  let shuttingDown = false;
  cluster.on('exit', (worker, code, signal) => {
    if (shuttingDown) return;
    console.error(`worker ${worker.process.pid} exited (${signal ?? code}); restarting`);
    cluster.fork();
  });
  const shutdown = () => {
    shuttingDown = true;
    for (const w of Object.values(cluster.workers ?? {})) w?.process.kill('SIGTERM');
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
  cluster.on('disconnect', () => {
    if (shuttingDown && Object.keys(cluster.workers ?? {}).length === 0) process.exit(0);
  });
}

async function runWorker() {
  const prisma = createClient();
  await configureConnection(prisma);
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

const main = cluster.isPrimary ? runPrimary : runWorker;
main().catch((err) => {
  console.error(err);
  process.exit(1);
});
