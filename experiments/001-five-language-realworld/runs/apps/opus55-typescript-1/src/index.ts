import cluster from "node:cluster";
import crypto from "node:crypto";
import { databasePath, host, port, workerCount } from "./config.js";

// The primary process prepares the database once, then forks one HTTP worker
// per available CPU core. Workers share the listening port through the
// cluster module and are restarted if they exit unexpectedly.
async function startPrimary() {
  const { prepareDatabase } = await import("./migrate.js");
  prepareDatabase(databasePath);

  // All workers must sign tokens with the same secret.
  process.env.JWT_SECRET ??= crypto.randomBytes(32).toString("hex");

  console.log(`Primary ${process.pid}: starting ${workerCount} workers on port ${port}`);
  for (let i = 0; i < workerCount; i++) cluster.fork();

  let shuttingDown = false;
  cluster.on("exit", (worker, code, signal) => {
    if (shuttingDown) return;
    console.error(`Worker ${worker.process.pid} exited (${signal ?? code}); restarting`);
    cluster.fork();
  });
  const shutdown = () => {
    shuttingDown = true;
    for (const worker of Object.values(cluster.workers ?? {})) worker?.kill();
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

async function startWorker() {
  const { configureConnection, prisma } = await import("./db.js");
  const { buildApp } = await import("./app.js");
  await configureConnection();
  const app = buildApp();
  await app.listen({ port, host });
  const close = async () => {
    await app.close();
    await prisma.$disconnect();
    process.exit(0);
  };
  process.on("SIGINT", close);
  process.on("SIGTERM", close);
}

const start = cluster.isPrimary ? startPrimary : startWorker;
start().catch((error) => {
  console.error(error);
  process.exit(1);
});
