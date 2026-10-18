import { randomBytes } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Prisma } from '@prisma/client';
import { prisma } from '../db';
import { readUserId, requireUserId } from '../auth';
import { forbidden, notFound, Validator } from '../errors';
import { articleInclude, commentInclude, toArticle, toComment } from '../serializers';
import * as check from '../validation';

type SlugParams = { slug: string };
type CommentParams = { slug: string; id: string };
type ListQuery = { tag?: string; author?: string; favorited?: string; limit?: string; offset?: string };

const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 100;

// Builds a URL-safe slug from the title plus a random suffix, so equal titles get distinct slugs.
function makeSlug(title: string): string {
  const base = title
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80);
  const suffix = randomBytes(4).toString('hex');
  return base ? `${base}-${suffix}` : suffix;
}

function parsePagination(query: ListQuery) {
  const toInt = (value: string | undefined, fallback: number) => {
    const n = Number.parseInt(value ?? '', 10);
    return Number.isFinite(n) && n >= 0 ? n : fallback;
  };
  return { take: Math.min(toInt(query.limit, DEFAULT_LIMIT), MAX_LIMIT), skip: toInt(query.offset, 0) };
}

// Newest first. The id breaks ties between articles created in the same millisecond.
const newestFirst: Prisma.ArticleOrderByWithRelationInput[] = [{ createdAt: 'desc' }, { id: 'desc' }];

async function listArticles(where: Prisma.ArticleWhereInput, query: ListQuery, viewerId: number | null) {
  const [articles, articlesCount] = await Promise.all([
    prisma.article.findMany({ where, orderBy: newestFirst, ...parsePagination(query), include: articleInclude(viewerId) }),
    prisma.article.count({ where }),
  ]);
  return { articles: articles.map((a) => toArticle(a, false)), articlesCount };
}

async function findArticle(slug: string, viewerId: number | null) {
  const article = await prisma.article.findUnique({ where: { slug }, include: articleInclude(viewerId) });
  if (!article) throw notFound('article');
  return article;
}

// Replaces the tags of an article, keeping the order given by the client.
async function setTags(tx: Prisma.TransactionClient, articleId: number, tags: string[]) {
  await tx.articleTag.deleteMany({ where: { articleId } });
  for (const [position, name] of tags.entries()) {
    const tag = await tx.tag.upsert({ where: { name }, create: { name }, update: {} });
    await tx.articleTag.create({ data: { articleId, tagId: tag.id, position } });
  }
}

export async function articleRoutes(app: FastifyInstance) {
  app.get<{ Querystring: ListQuery }>('/api/articles', async (request) => {
    const { tag, author, favorited } = request.query;
    const where: Prisma.ArticleWhereInput = {};
    if (tag) where.tags = { some: { tag: { name: tag } } };
    if (author) where.author = { username: author };
    if (favorited) where.favorites = { some: { user: { username: favorited } } };
    return listArticles(where, request.query, readUserId(request));
  });

  app.get<{ Querystring: ListQuery }>('/api/articles/feed', async (request) => {
    const viewerId = requireUserId(request);
    const where: Prisma.ArticleWhereInput = { author: { followers: { some: { followerId: viewerId } } } };
    return listArticles(where, request.query, viewerId);
  });

  app.get<{ Params: SlugParams }>('/api/articles/:slug', async (request) => {
    return { article: toArticle(await findArticle(request.params.slug, readUserId(request))) };
  });

  app.post('/api/articles', async (request, reply) => {
    const userId = requireUserId(request);
    const input = check.wrapper(request.body, 'article');
    const v = new Validator();
    const title = check.requiredString(v, input, 'title');
    const description = check.requiredString(v, input, 'description');
    const body = check.requiredString(v, input, 'body');
    const tags = check.tagList(v, input) ?? [];
    v.throwIfInvalid();

    const created = await prisma.$transaction(async (tx) => {
      const article = await tx.article.create({
        data: { slug: makeSlug(title!), title: title!, description: description!, body: body!, authorId: userId },
      });
      await setTags(tx, article.id, tags);
      return article;
    });
    return reply.code(201).send({ article: toArticle(await findArticle(created.slug, userId)) });
  });

  app.put<{ Params: SlugParams }>('/api/articles/:slug', async (request) => {
    const userId = requireUserId(request);
    const input = check.wrapper(request.body, 'article');
    const v = new Validator();
    const title = check.optionalNonEmptyString(v, input, 'title');
    const description = check.optionalNonEmptyString(v, input, 'description');
    const body = check.optionalNonEmptyString(v, input, 'body');
    const tags = check.tagList(v, input);
    v.throwIfInvalid();

    const existing = await prisma.article.findUnique({ where: { slug: request.params.slug } });
    if (!existing) throw notFound('article');
    if (existing.authorId !== userId) throw forbidden('article');

    // updatedAt must move forward even if the update lands in the same millisecond as the last write.
    const now = Math.max(Date.now(), existing.updatedAt.getTime() + 1);
    const data: Prisma.ArticleUncheckedUpdateInput = { updatedAt: new Date(now) };
    if (title !== undefined && title !== existing.title) {
      data.title = title;
      data.slug = makeSlug(title);
    }
    if (description !== undefined) data.description = description;
    if (body !== undefined) data.body = body;

    const updated = await prisma.$transaction(async (tx) => {
      const article = await tx.article.update({ where: { id: existing.id }, data });
      if (tags !== undefined) await setTags(tx, article.id, tags);
      return article;
    });
    return { article: toArticle(await findArticle(updated.slug, userId)) };
  });

  app.delete<{ Params: SlugParams }>('/api/articles/:slug', async (request, reply) => {
    const userId = requireUserId(request);
    const existing = await prisma.article.findUnique({ where: { slug: request.params.slug } });
    if (!existing) throw notFound('article');
    if (existing.authorId !== userId) throw forbidden('article');
    await prisma.article.delete({ where: { id: existing.id } });
    return reply.code(204).send();
  });

  app.post<{ Params: SlugParams }>('/api/articles/:slug/favorite', async (request) => {
    const userId = requireUserId(request);
    const article = await findArticle(request.params.slug, userId);
    await prisma.favorite.upsert({
      where: { userId_articleId: { userId, articleId: article.id } },
      create: { userId, articleId: article.id },
      update: {},
    });
    return { article: toArticle(await findArticle(article.slug, userId)) };
  });

  app.delete<{ Params: SlugParams }>('/api/articles/:slug/favorite', async (request) => {
    const userId = requireUserId(request);
    const article = await findArticle(request.params.slug, userId);
    await prisma.favorite.deleteMany({ where: { userId, articleId: article.id } });
    return { article: toArticle(await findArticle(article.slug, userId)) };
  });

  app.get<{ Params: SlugParams }>('/api/articles/:slug/comments', async (request) => {
    const viewerId = readUserId(request);
    const article = await prisma.article.findUnique({ where: { slug: request.params.slug }, select: { id: true } });
    if (!article) throw notFound('article');
    const comments = await prisma.comment.findMany({
      where: { articleId: article.id },
      orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
      include: commentInclude(viewerId),
    });
    return { comments: comments.map(toComment) };
  });

  app.post<{ Params: SlugParams }>('/api/articles/:slug/comments', async (request, reply) => {
    const userId = requireUserId(request);
    const input = check.wrapper(request.body, 'comment');
    const v = new Validator();
    const body = check.requiredString(v, input, 'body');
    v.throwIfInvalid();

    const article = await prisma.article.findUnique({ where: { slug: request.params.slug }, select: { id: true } });
    if (!article) throw notFound('article');
    const comment = await prisma.comment.create({
      data: { body: body!, articleId: article.id, authorId: userId },
      include: commentInclude(userId),
    });
    return reply.code(201).send({ comment: toComment(comment) });
  });

  app.delete<{ Params: CommentParams }>('/api/articles/:slug/comments/:id', async (request, reply) => {
    const userId = requireUserId(request);
    const article = await prisma.article.findUnique({ where: { slug: request.params.slug }, select: { id: true } });
    if (!article) throw notFound('article');
    const id = Number(request.params.id);
    const comment = Number.isSafeInteger(id)
      ? await prisma.comment.findFirst({ where: { id, articleId: article.id } })
      : null;
    if (!comment) throw notFound('comment');
    if (comment.authorId !== userId) throw forbidden('comment');
    await prisma.comment.delete({ where: { id: comment.id } });
    return reply.code(204).send();
  });
}
