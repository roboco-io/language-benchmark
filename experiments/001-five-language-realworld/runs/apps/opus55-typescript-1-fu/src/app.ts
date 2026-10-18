import Fastify, { type FastifyError, type FastifyInstance } from "fastify";
import { HttpError } from "./errors.js";
import { articleRoutes } from "./routes/articles.js";
import { commentRoutes } from "./routes/comments.js";
import { profileRoutes } from "./routes/profiles.js";
import { tagRoutes } from "./routes/tags.js";
import { userRoutes } from "./routes/users.js";

export function buildApp(): FastifyInstance {
  const app = Fastify({
    logger: process.env.LOG_LEVEL ? { level: process.env.LOG_LEVEL } : false,
    bodyLimit: 1024 * 1024,
  });

  // Accept requests that declare a JSON content type but send no body
  // (for example POST /favorite), which Fastify rejects by default.
  app.removeContentTypeParser("application/json");
  app.addContentTypeParser("application/json", { parseAs: "string" }, (_request, body, done) => {
    const text = (body as string).trim();
    if (text === "") return done(null, undefined);
    try {
      done(null, JSON.parse(text));
    } catch {
      done(new HttpError(422, { body: ["is not valid JSON"] }), undefined);
    }
  });

  app.setErrorHandler((error: FastifyError | HttpError, request, reply) => {
    if (error instanceof HttpError) {
      return reply.code(error.statusCode).send({ errors: error.errors });
    }
    const status = error.statusCode && error.statusCode >= 400 ? error.statusCode : 500;
    if (status >= 500) request.log.error(error);
    return reply
      .code(status)
      .send({ errors: { [status >= 500 ? "server" : "request"]: [status >= 500 ? "internal error" : error.message] } });
  });

  app.setNotFoundHandler((_request, reply) => {
    reply.code(404).send({ errors: { route: ["not found"] } });
  });

  app.register(userRoutes);
  app.register(profileRoutes);
  app.register(articleRoutes);
  app.register(commentRoutes);
  app.register(tagRoutes);

  return app;
}
