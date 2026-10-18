import Fastify, { type FastifyError, type FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';
import { HttpError } from './errors';
import articleRoutes from './routes/articles';
import commentRoutes from './routes/comments';
import profileRoutes from './routes/profiles';
import tagRoutes from './routes/tags';
import userRoutes from './routes/users';

export function buildApp(prisma: PrismaClient): FastifyInstance {
  const app = Fastify({
    logger: { level: process.env.LOG_LEVEL ?? 'warn' },
    bodyLimit: 1024 * 1024,
  });

  // Accept empty JSON bodies (e.g. POST /favorite sent with a JSON content type) as `{}`.
  app.addContentTypeParser('application/json', { parseAs: 'string' }, (_request, body, done) => {
    const text = typeof body === 'string' ? body : body.toString('utf8');
    if (text.trim() === '') return done(null, {});
    try {
      done(null, JSON.parse(text));
    } catch {
      done(new HttpError(400, { body: ['is not valid JSON'] }), undefined);
    }
  });

  app.addHook('onSend', async (_request, reply, payload) => {
    reply.header('Access-Control-Allow-Origin', '*');
    return payload;
  });
  app.options('/*', async (_request, reply) =>
    reply
      .header('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS')
      .header('Access-Control-Allow-Headers', 'Authorization,Content-Type')
      .code(204)
      .send(),
  );

  app.setErrorHandler((error: FastifyError | HttpError, request, reply) => {
    if (error instanceof HttpError) {
      return reply.code(error.statusCode).send({ errors: error.errors });
    }
    const status = error.statusCode ?? 500;
    if (status >= 500) {
      request.log.error(error);
      return reply.code(500).send({ errors: { server: ['internal error'] } });
    }
    return reply.code(status).send({ errors: { request: [error.message] } });
  });

  app.setNotFoundHandler((_request, reply) => reply.code(404).send({ errors: { route: ['not found'] } }));

  app.get('/health', async () => ({ status: 'ok' }));
  const options = { prisma };
  app.register(userRoutes, options);
  app.register(profileRoutes, options);
  app.register(articleRoutes, options);
  app.register(commentRoutes, options);
  app.register(tagRoutes, options);
  return app;
}
