import cluster from 'node:cluster';
import { availableParallelism } from 'node:os';

const port = Number(process.env.PORT ?? 8000);
const host = process.env.HOST ?? '0.0.0.0';

// The primary process forks one worker per available CPU core. Workers share
// the listening socket, and the primary replaces any worker that exits.
// WEB_CONCURRENCY can override the worker count.
if (cluster.isPrimary) {
  void startPrimary();
} else {
  void startWorker();
}

async function startPrimary() {
  const { configureDatabase, prisma } = await import('./db');
  await configureDatabase();
  await prisma.$disconnect();

  const workers = Number(process.env.WEB_CONCURRENCY) || availableParallelism();
  console.log(`Primary ${process.pid} starting ${workers} workers on port ${port}`);
  for (let i = 0; i < workers; i++) cluster.fork();

  let shuttingDown = false;
  cluster.on('exit', (worker, code, signal) => {
    if (shuttingDown) return;
    console.error(`Worker ${worker.process.pid} exited (code ${code}, signal ${signal}); restarting`);
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

async function startWorker() {
  const { buildApp } = await import('./app');
  const { configureDatabase, prisma } = await import('./db');
  await configureDatabase();
  const app = buildApp();
  await app.listen({ port, host });

  const stop = async () => {
    await app.close();
    await prisma.$disconnect();
    process.exit(0);
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
