import cluster from 'node:cluster';
import { availableParallelism } from 'node:os';
import { buildApp } from './app';
import { initSchema, prisma } from './db';

const port = Number(process.env.PORT ?? 3000);
const host = process.env.HOST ?? '0.0.0.0';

async function runWorker(): Promise<void> {
  await initSchema();
  const app = buildApp();
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
  // Create the schema once before workers start so they do not race on DDL.
  await initSchema();
  await prisma.$disconnect();

  const workers = Number(process.env.WORKERS) || availableParallelism();
  for (let i = 0; i < workers; i++) cluster.fork();

  let stopping = false;
  cluster.on('exit', (worker, code, signal) => {
    if (stopping) return;
    console.error(`worker ${worker.process.pid} exited (code=${code}, signal=${signal}); restarting`);
    cluster.fork();
  });
  const stop = () => {
    stopping = true;
    for (const w of Object.values(cluster.workers ?? {})) w?.process.kill('SIGTERM');
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
  console.log(`primary ${process.pid} listening on :${port} with ${workers} workers`);
}

const main = cluster.isPrimary ? runPrimary : runWorker;
main().catch((err) => {
  console.error(err);
  process.exit(1);
});
