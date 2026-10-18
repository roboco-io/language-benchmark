import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';
import { optionalUserId, requireUserId } from '../auth';
import { forbidden, notFound, Validator } from '../errors';
import { commentInclude, toComment } from '../serializers';
import { BLANK, envelope, isNonBlankString } from '../validation';
import { loadCurrentUser } from './users';

type SlugParams = { slug: string };
type CommentParams = SlugParams & { id: string };

export default async function commentRoutes(app: FastifyInstance, { prisma }: { prisma: PrismaClient }) {
  async function findArticleId(slug: string): Promise<number> {
    const article = await prisma.article.findUnique({ where: { slug }, select: { id: true } });
    if (!article) throw notFound('article');
    return article.id;
  }

  app.get<{ Params: SlugParams }>('/api/articles/:slug/comments', async (request) => {
    const viewerId = optionalUserId(request);
    const articleId = await findArticleId(request.params.slug);
    const comments = await prisma.comment.findMany({
      where: { articleId },
      include: commentInclude(viewerId),
      orderBy: { id: 'asc' },
    });
    return { comments: comments.map(toComment) };
  });

  app.post<{ Params: SlugParams }>('/api/articles/:slug/comments', async (request, reply) => {
    const userId = requireUserId(request);
    const input = envelope(request.body, 'comment');
    const validator = new Validator();
    if (!isNonBlankString(input.body)) validator.add('body', BLANK);
    validator.throwIfAny();
    await loadCurrentUser(prisma, userId);
    const articleId = await findArticleId(request.params.slug);

    const now = new Date();
    const comment = await prisma.comment.create({
      data: { body: input.body as string, articleId, authorId: userId, createdAt: now, updatedAt: now },
      include: commentInclude(userId),
    });
    return reply.code(201).send({ comment: toComment(comment) });
  });

  app.delete<{ Params: CommentParams }>('/api/articles/:slug/comments/:id', async (request, reply) => {
    const userId = requireUserId(request);
    const articleId = await findArticleId(request.params.slug);
    const id = /^\d+$/.test(request.params.id) ? Number(request.params.id) : NaN;
    const comment = Number.isSafeInteger(id)
      ? await prisma.comment.findUnique({ where: { id }, select: { articleId: true, authorId: true } })
      : null;
    if (!comment || comment.articleId !== articleId) throw notFound('comment');
    if (comment.authorId !== userId) throw forbidden('comment');
    await prisma.comment.deleteMany({ where: { id } });
    return reply.code(204).send();
  });
}
