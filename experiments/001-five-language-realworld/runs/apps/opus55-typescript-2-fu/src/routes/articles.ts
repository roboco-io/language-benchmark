import crypto from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Prisma, PrismaClient } from '@prisma/client';
import { optionalUserId, requireUserId } from '../auth';
import { forbidden, notFound, Validator } from '../errors';
import { isUniqueViolation } from '../prismaErrors';
import { articleInclude, toArticle } from '../serializers';
import { BLANK, envelope, isNonBlankString, isPresent, parseCount } from '../validation';
import { loadCurrentUser } from './users';

type SlugParams = { slug: string };
type ListQuery = { tag?: string; author?: string; favorited?: string; limit?: string; offset?: string };

const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 100;
const MAX_ATTEMPTS = 5;

export function slugify(title: string): string {
  const slug = title
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80)
    .replace(/-+$/g, '');
  return slug || 'article';
}

/** The first attempt uses the plain slug; retries (after a collision) append a random suffix. */
function candidateSlug(title: string, attempt: number): string {
  const base = slugify(title);
  return attempt === 0 ? base : `${base}-${crypto.randomBytes(4).toString('hex')}`;
}

/** Validates `tagList`: an array of strings. Returns trimmed, de-duplicated tags in their original order. */
function parseTagList(value: unknown, validator: Validator): string[] | undefined {
  if (!Array.isArray(value) || !value.every((tag) => typeof tag === 'string')) {
    validator.add('tagList', 'must be an array of strings');
    return undefined;
  }
  return [...new Set((value as string[]).map((tag) => tag.trim()).filter((tag) => tag.length > 0))];
}

function tagCreates(tags: string[]) {
  return tags.map((name, position) => ({
    position,
    tag: { connectOrCreate: { where: { name }, create: { name } } },
  }));
}

/** Retries an insert/update when it races with another worker on a unique slug or tag name. */
async function withUniqueRetry<T>(operation: (attempt: number) => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await operation(attempt);
    } catch (error) {
      if (attempt + 1 >= MAX_ATTEMPTS || !isUniqueViolation(error)) throw error;
    }
  }
}

export default async function articleRoutes(app: FastifyInstance, { prisma }: { prisma: PrismaClient }) {
  async function findArticle(slug: string) {
    const article = await prisma.article.findUnique({ where: { slug }, select: { id: true, authorId: true } });
    if (!article) throw notFound('article');
    return article;
  }

  async function renderArticle(id: number, viewerId?: number) {
    const article = await prisma.article.findUniqueOrThrow({ where: { id }, include: articleInclude(viewerId) });
    return { article: toArticle(article) };
  }

  async function listArticles(where: Prisma.ArticleWhereInput, query: ListQuery, viewerId?: number) {
    const limit = parseCount(query.limit, DEFAULT_LIMIT, MAX_LIMIT);
    const offset = parseCount(query.offset, 0);
    const [articles, articlesCount] = await prisma.$transaction([
      prisma.article.findMany({
        where,
        include: articleInclude(viewerId),
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        take: limit,
        skip: offset,
      }),
      prisma.article.count({ where }),
    ]);
    return { articles: articles.map((article) => toArticle(article, false)), articlesCount };
  }

  app.get<{ Querystring: ListQuery }>('/api/articles', async (request) => {
    const viewerId = optionalUserId(request);
    const { tag, author, favorited } = request.query;
    const where: Prisma.ArticleWhereInput = {};
    if (tag) where.tags = { some: { tag: { name: tag } } };
    if (author) where.author = { username: author };
    if (favorited) where.favorites = { some: { user: { username: favorited } } };
    return listArticles(where, request.query, viewerId);
  });

  app.get<{ Querystring: ListQuery }>('/api/articles/feed', async (request) => {
    const viewerId = requireUserId(request);
    await loadCurrentUser(prisma, viewerId);
    return listArticles({ author: { followers: { some: { followerId: viewerId } } } }, request.query, viewerId);
  });

  app.get<{ Params: SlugParams }>('/api/articles/:slug', async (request) => {
    const viewerId = optionalUserId(request);
    const article = await findArticle(request.params.slug);
    return renderArticle(article.id, viewerId);
  });

  app.post('/api/articles', async (request, reply) => {
    const userId = requireUserId(request);
    const input = envelope(request.body, 'article');
    const validator = new Validator();
    for (const field of ['title', 'description', 'body'] as const) {
      if (!isNonBlankString(input[field])) validator.add(field, BLANK);
    }
    const tags = isPresent(input, 'tagList') ? parseTagList(input.tagList, validator) : [];
    validator.throwIfAny();
    await loadCurrentUser(prisma, userId);

    const title = input.title as string;
    const now = new Date();
    const created = await withUniqueRetry((attempt) =>
      prisma.article.create({
        data: {
          slug: candidateSlug(title, attempt),
          title,
          description: input.description as string,
          body: input.body as string,
          createdAt: now,
          updatedAt: now,
          author: { connect: { id: userId } },
          tags: { create: tagCreates(tags ?? []) },
        },
        select: { id: true },
      }),
    );
    return reply.code(201).send(await renderArticle(created.id, userId));
  });

  app.put<{ Params: SlugParams }>('/api/articles/:slug', async (request) => {
    const userId = requireUserId(request);
    const article = await findArticle(request.params.slug);
    if (article.authorId !== userId) throw forbidden('article');

    const input = envelope(request.body, 'article');
    const validator = new Validator();
    const data: Prisma.ArticleUpdateInput = {};
    for (const field of ['title', 'description', 'body'] as const) {
      if (!isPresent(input, field)) continue;
      const value = input[field];
      if (isNonBlankString(value)) data[field] = value;
      else validator.add(field, BLANK);
    }
    const tags = isPresent(input, 'tagList') ? parseTagList(input.tagList, validator) : undefined;
    validator.throwIfAny();

    const current = await prisma.article.findUniqueOrThrow({ where: { id: article.id }, select: { title: true } });
    const titleChanged = typeof data.title === 'string' && data.title !== current.title;
    await withUniqueRetry((attempt) =>
      prisma.article.update({
        where: { id: article.id },
        data: {
          ...data,
          ...(titleChanged ? { slug: candidateSlug(data.title as string, attempt) } : {}),
          ...(tags ? { tags: { deleteMany: {}, create: tagCreates(tags) } } : {}),
          updatedAt: new Date(),
        },
        select: { id: true },
      }),
    );
    return renderArticle(article.id, userId);
  });

  app.delete<{ Params: SlugParams }>('/api/articles/:slug', async (request, reply) => {
    const userId = requireUserId(request);
    const article = await findArticle(request.params.slug);
    if (article.authorId !== userId) throw forbidden('article');
    await prisma.article.deleteMany({ where: { id: article.id } });
    return reply.code(204).send();
  });

  app.post<{ Params: SlugParams }>('/api/articles/:slug/favorite', async (request) => {
    const userId = requireUserId(request);
    await loadCurrentUser(prisma, userId);
    const article = await findArticle(request.params.slug);
    await prisma.favorite.upsert({
      where: { userId_articleId: { userId, articleId: article.id } },
      create: { userId, articleId: article.id },
      update: {},
    });
    return renderArticle(article.id, userId);
  });

  app.delete<{ Params: SlugParams }>('/api/articles/:slug/favorite', async (request) => {
    const userId = requireUserId(request);
    await loadCurrentUser(prisma, userId);
    const article = await findArticle(request.params.slug);
    await prisma.favorite.deleteMany({ where: { userId, articleId: article.id } });
    return renderArticle(article.id, userId);
  });
}
