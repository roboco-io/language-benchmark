import cluster from 'cluster';
import os from 'os';
import { buildApp } from './app';
import { configureConnection, initSchema, prisma } from './db';

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '0.0.0.0';
const WORKERS = Math.max(1, os.availableParallelism());

async function runWorker() {
  await configureConnection();
  const app = buildApp();
  await app.listen({ port: PORT, host: HOST });
  const shutdown = async () => {
    await app.close();
    await prisma.$disconnect();
    process.exit(0);
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

async function runPrimary() {
  // The primary prepares the schema once so workers never race on DDL.
  await initSchema();
  await prisma.$disconnect();
  console.log(`primary ${process.pid}: starting ${WORKERS} workers on port ${PORT}`);
  for (let i = 0; i < WORKERS; i++) cluster.fork();
  let stopping = false;
  cluster.on('exit', (w, code) => {
    if (stopping) return;
    console.error(`worker ${w.process.pid} exited (${code}); restarting`);
    cluster.fork();
  });
  const stop = () => {
    stopping = true;
    for (const w of Object.values(cluster.workers || {})) w?.process.kill('SIGTERM');
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}

const main = cluster.isPrimary ? runPrimary : runWorker;
main().catch((e) => {
  console.error(e);
  process.exit(1);
});
