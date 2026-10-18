import Fastify, { type FastifyError } from 'fastify';
import { HttpError } from './errors';
import { articleRoutes } from './routes/articles';
import { profileRoutes } from './routes/profiles';
import { tagRoutes } from './routes/tags';
import { userRoutes } from './routes/users';

export function buildApp() {
  const app = Fastify({ logger: process.env.LOG_LEVEL ? { level: process.env.LOG_LEVEL } : false });

  // Allow browser front ends on other origins to call the API.
  app.addHook('onRequest', async (request, reply) => {
    reply.header('Access-Control-Allow-Origin', '*');
    reply.header('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    reply.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
    if (request.method === 'OPTIONS') return reply.code(204).send();
  });

  app.setErrorHandler((error: FastifyError | HttpError, request, reply) => {
    if (error instanceof HttpError) {
      return reply.code(error.statusCode).send({ errors: error.errors });
    }
    const status = error.statusCode ?? 500;
    if (status >= 500) {
      request.log.error(error);
      return reply.code(500).send({ errors: { server: ['internal error'] } });
    }
    // Framework errors such as malformed JSON bodies.
    return reply.code(status === 400 ? 422 : status).send({ errors: { body: [error.message] } });
  });

  app.setNotFoundHandler((_request, reply) => {
    reply.code(404).send({ errors: { route: ['not found'] } });
  });

  app.register(userRoutes);
  app.register(profileRoutes);
  app.register(articleRoutes);
  app.register(tagRoutes);
  return app;
}
