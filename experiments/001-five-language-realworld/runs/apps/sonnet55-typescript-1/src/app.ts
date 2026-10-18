import Fastify, { FastifyInstance, FastifyRequest } from 'fastify';
import bcrypt from 'bcryptjs';
import { randomBytes } from 'node:crypto';
import { Prisma, PrismaClient } from '@prisma/client';
import { requireUserId, signToken, userIdFromRequest } from './auth';
import { conflict, Errors, forbidden, HttpError, notFound, unprocessable } from './http';

const MIN_PASSWORD_LENGTH = 8;
const BCRYPT_ROUNDS = 10;

type Body = Record<string, unknown>;

const isObject = (v: unknown): v is Body => typeof v === 'object' && v !== null && !Array.isArray(v);
const isBlank = (v: unknown) => typeof v !== 'string' || v.trim() === '';

/** Extracts `{ [key]: {...} }` from a request body or fails with 422. */
function wrapped(req: FastifyRequest, key: string): Body {
  const body = req.body;
  if (!isObject(body) || !isObject(body[key])) {
    throw unprocessable({ [key]: ["can't be blank"] });
  }
  return body[key] as Body;
}

function throwIfErrors(errors: Errors) {
  if (Object.keys(errors).length > 0) throw unprocessable(errors);
}

function validatePassword(errors: Errors, value: unknown) {
  if (isBlank(value)) errors.password = ["can't be blank"];
  else if ((value as string).length < MIN_PASSWORD_LENGTH) {
    errors.password = [`is too short (minimum is ${MIN_PASSWORD_LENGTH} characters)`];
  }
}

/** Optional text field: empty string and null both clear it. */
function nullableText(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null;
  return String(value);
}

type UserRow = { id: number; email: string; username: string; bio: string | null; image: string | null };

const userJson = (u: UserRow) => ({
  user: { email: u.email, username: u.username, bio: u.bio, image: u.image, token: signToken(u.id) },
});

function slugify(title: string): string {
  const base = title
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '');
  return base || 'article';
}

function parseTags(value: unknown, errors: Errors): string[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.some((t) => typeof t !== 'string')) {
    errors.tagList = ['must be a list of strings'];
    return undefined;
  }
  return [...new Set((value as string[]).map((t) => t.trim()).filter(Boolean))];
}

function parseIntParam(value: unknown, fallback: number, max: number): number {
  const n = Number.parseInt(String(value ?? ''), 10);
  if (!Number.isFinite(n) || n < 0) return fallback;
  return Math.min(n, max);
}

export function buildApp(prisma: PrismaClient): FastifyInstance {
  const app = Fastify({ logger: false });

  app.setErrorHandler((err: Error & { statusCode?: number }, _req, reply) => {
    if (err instanceof HttpError) return reply.code(err.status).send({ errors: err.errors });
    if (err.statusCode && err.statusCode < 500) {
      return reply.code(422).send({ errors: { body: [err.message] } });
    }
    console.error(err);
    return reply.code(500).send({ errors: { server: ['internal error'] } });
  });
  app.setNotFoundHandler((_req, reply) => reply.code(404).send({ errors: { route: ['not found'] } }));

  // ---------- serializers ----------

  const articleInclude = (viewerId: number | null) =>
    ({
      author: {
        include: { followers: viewerId ? { where: { followerId: viewerId } } : false },
      },
      tags: { orderBy: { name: 'asc' } },
      favorites: viewerId ? { where: { userId: viewerId } } : false,
      _count: { select: { favorites: true } },
    }) satisfies Prisma.ArticleInclude;

  type ArticleFull = Prisma.ArticleGetPayload<{ include: ReturnType<typeof articleInclude> }>;

  const profileJson = (
    u: { username: string; bio: string | null; image: string | null },
    following: boolean,
  ) => ({ username: u.username, bio: u.bio, image: u.image, following });

  const articleJson = (a: ArticleFull, withBody: boolean) => ({
    slug: a.slug,
    title: a.title,
    description: a.description,
    ...(withBody ? { body: a.body } : {}),
    tagList: a.tags.map((t) => t.name),
    createdAt: a.createdAt.toISOString(),
    updatedAt: a.updatedAt.toISOString(),
    favorited: Array.isArray(a.favorites) && a.favorites.length > 0,
    favoritesCount: a._count.favorites,
    author: profileJson(a.author, Array.isArray(a.author.followers) && a.author.followers.length > 0),
  });

  async function loadArticle(slug: string, viewerId: number | null): Promise<ArticleFull> {
    const article = await prisma.article.findUnique({ where: { slug }, include: articleInclude(viewerId) });
    if (!article) throw notFound('article');
    return article;
  }

  async function listArticles(where: Prisma.ArticleWhereInput, req: FastifyRequest, viewerId: number | null) {
    const q = req.query as Record<string, unknown>;
    const limit = parseIntParam(q.limit, 20, 100);
    const offset = parseIntParam(q.offset, 0, Number.MAX_SAFE_INTEGER);
    const [articles, articlesCount] = await Promise.all([
      prisma.article.findMany({
        where,
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        take: limit,
        skip: offset,
        include: articleInclude(viewerId),
      }),
      prisma.article.count({ where }),
    ]);
    return { articles: articles.map((a) => articleJson(a, false)), articlesCount };
  }

  async function findUserByName(username: string) {
    const user = await prisma.user.findUnique({ where: { username } });
    if (!user) throw notFound('profile');
    return user;
  }

  // ---------- users ----------

  app.post('/api/users', async (req, reply) => {
    const input = wrapped(req, 'user');
    const errors: Errors = {};
    if (isBlank(input.username)) errors.username = ["can't be blank"];
    if (isBlank(input.email)) errors.email = ["can't be blank"];
    validatePassword(errors, input.password);
    throwIfErrors(errors);

    const username = (input.username as string).trim();
    const email = (input.email as string).trim();
    const taken: Errors = {};
    if (await prisma.user.findUnique({ where: { username } })) taken.username = ['has already been taken'];
    if (await prisma.user.findUnique({ where: { email } })) taken.email = ['has already been taken'];
    if (Object.keys(taken).length) throw conflict(taken);

    try {
      const user = await prisma.user.create({
        data: { username, email, password: await bcrypt.hash(input.password as string, BCRYPT_ROUNDS) },
      });
      return reply.code(201).send(userJson(user));
    } catch (e) {
      throw uniqueViolation(e);
    }
  });

  app.post('/api/users/login', async (req, reply) => {
    const input = wrapped(req, 'user');
    const errors: Errors = {};
    if (isBlank(input.email)) errors.email = ["can't be blank"];
    if (isBlank(input.password)) errors.password = ["can't be blank"];
    throwIfErrors(errors);

    const user = await prisma.user.findUnique({ where: { email: (input.email as string).trim() } });
    const ok = user && (await bcrypt.compare(input.password as string, user.password));
    if (!user || !ok) throw new HttpError(401, { credentials: ['invalid'] });
    return reply.send(userJson(user));
  });

  app.get('/api/user', async (req) => {
    const user = await prisma.user.findUnique({ where: { id: requireUserId(req) } });
    if (!user) throw new HttpError(401, { token: ['is invalid'] });
    return userJson(user);
  });

  app.put('/api/user', async (req) => {
    const id = requireUserId(req);
    const input = wrapped(req, 'user');
    const errors: Errors = {};
    const data: Prisma.UserUpdateInput = {};

    for (const field of ['username', 'email'] as const) {
      if (field in input) {
        if (isBlank(input[field])) errors[field] = ["can't be blank"];
        else data[field] = (input[field] as string).trim();
      }
    }
    if ('password' in input) {
      validatePassword(errors, input.password);
      if (!errors.password) data.password = await bcrypt.hash(input.password as string, BCRYPT_ROUNDS);
    }
    if ('bio' in input) data.bio = nullableText(input.bio);
    if ('image' in input) data.image = nullableText(input.image);
    throwIfErrors(errors);

    const taken: Errors = {};
    if (typeof data.username === 'string') {
      const other = await prisma.user.findUnique({ where: { username: data.username } });
      if (other && other.id !== id) taken.username = ['has already been taken'];
    }
    if (typeof data.email === 'string') {
      const other = await prisma.user.findUnique({ where: { email: data.email } });
      if (other && other.id !== id) taken.email = ['has already been taken'];
    }
    if (Object.keys(taken).length) throw conflict(taken);

    try {
      return userJson(await prisma.user.update({ where: { id }, data }));
    } catch (e) {
      throw uniqueViolation(e);
    }
  });

  // ---------- profiles ----------

  app.get('/api/profiles/:username', async (req) => {
    const viewerId = userIdFromRequest(req);
    const user = await findUserByName((req.params as { username: string }).username);
    const following = viewerId
      ? !!(await prisma.follow.findUnique({
          where: { followerId_followedId: { followerId: viewerId, followedId: user.id } },
        }))
      : false;
    return { profile: profileJson(user, following) };
  });

  app.post('/api/profiles/:username/follow', async (req) => {
    const viewerId = requireUserId(req);
    const user = await findUserByName((req.params as { username: string }).username);
    await prisma.follow.upsert({
      where: { followerId_followedId: { followerId: viewerId, followedId: user.id } },
      create: { followerId: viewerId, followedId: user.id },
      update: {},
    });
    return { profile: profileJson(user, true) };
  });

  app.delete('/api/profiles/:username/follow', async (req) => {
    const viewerId = requireUserId(req);
    const user = await findUserByName((req.params as { username: string }).username);
    await prisma.follow.deleteMany({ where: { followerId: viewerId, followedId: user.id } });
    return { profile: profileJson(user, false) };
  });

  // ---------- articles ----------

  app.get('/api/articles', async (req) => {
    const viewerId = userIdFromRequest(req);
    const q = req.query as Record<string, string | undefined>;
    const where: Prisma.ArticleWhereInput = {};
    if (q.tag) where.tags = { some: { name: q.tag } };
    if (q.author) where.author = { username: q.author };
    if (q.favorited) where.favorites = { some: { user: { username: q.favorited } } };
    return listArticles(where, req, viewerId);
  });

  // Registered before /:slug; find-my-way prefers the static segment anyway.
  app.get('/api/articles/feed', async (req) => {
    const viewerId = requireUserId(req);
    return listArticles({ author: { followers: { some: { followerId: viewerId } } } }, req, viewerId);
  });

  app.get('/api/articles/:slug', async (req) => {
    const viewerId = userIdFromRequest(req);
    return { article: articleJson(await loadArticle((req.params as { slug: string }).slug, viewerId), true) };
  });

  app.post('/api/articles', async (req, reply) => {
    const authorId = requireUserId(req);
    const input = wrapped(req, 'article');
    const errors: Errors = {};
    for (const f of ['title', 'description', 'body'] as const) {
      if (isBlank(input[f])) errors[f] = ["can't be blank"];
    }
    const tags = parseTags(input.tagList, errors);
    throwIfErrors(errors);

    const title = (input.title as string).trim();
    const base = slugify(title);
    for (let attempt = 0; attempt < 5; attempt++) {
      const slug = attempt === 0 && !(await prisma.article.findUnique({ where: { slug: base } }))
        ? base
        : `${base}-${randomBytes(4).toString('hex')}`;
      try {
        const created = await prisma.article.create({
          data: {
            slug,
            title,
            description: input.description as string,
            body: input.body as string,
            authorId,
            tags: { connectOrCreate: (tags ?? []).map((name) => ({ where: { name }, create: { name } })) },
          },
          include: articleInclude(authorId),
        });
        return reply.code(201).send({ article: articleJson(created, true) });
      } catch (e) {
        if (!(e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002')) throw e;
        // Slug collision under concurrency (or tag-create race): retry with a random suffix.
      }
    }
    throw new HttpError(500, { server: ['could not allocate slug'] });
  });

  app.put('/api/articles/:slug', async (req) => {
    const userId = requireUserId(req);
    const slug = (req.params as { slug: string }).slug;
    const input = wrapped(req, 'article');
    const existing = await prisma.article.findUnique({ where: { slug } });
    if (!existing) throw notFound('article');
    if (existing.authorId !== userId) throw forbidden('article');

    const errors: Errors = {};
    const data: Prisma.ArticleUpdateInput = {};
    for (const f of ['title', 'description', 'body'] as const) {
      if (f in input) {
        if (isBlank(input[f])) errors[f] = ["can't be blank"];
        else data[f] = f === 'title' ? (input[f] as string).trim() : (input[f] as string);
      }
    }
    if ('tagList' in input) {
      const tags = parseTags(input.tagList, errors);
      if (input.tagList === null) errors.tagList = ["can't be null"];
      if (tags) {
        data.tags = { set: [], connectOrCreate: tags.map((name) => ({ where: { name }, create: { name } })) };
      }
    }
    throwIfErrors(errors);

    // Always bump updatedAt, even when only the (unchanged) tag set was submitted.
    data.updatedAt = new Date();
    const updated = await prisma.article.update({
      where: { slug },
      data,
      include: articleInclude(userId),
    });
    return { article: articleJson(updated, true) };
  });

  app.delete('/api/articles/:slug', async (req, reply) => {
    const userId = requireUserId(req);
    const slug = (req.params as { slug: string }).slug;
    const existing = await prisma.article.findUnique({ where: { slug } });
    if (!existing) throw notFound('article');
    if (existing.authorId !== userId) throw forbidden('article');
    await prisma.article.delete({ where: { slug } });
    return reply.code(204).send();
  });

  // ---------- favorites ----------

  app.post('/api/articles/:slug/favorite', async (req) => {
    const userId = requireUserId(req);
    const article = await loadArticle((req.params as { slug: string }).slug, userId);
    await prisma.favorite.upsert({
      where: { userId_articleId: { userId, articleId: article.id } },
      create: { userId, articleId: article.id },
      update: {},
    });
    return { article: articleJson(await loadArticle(article.slug, userId), true) };
  });

  app.delete('/api/articles/:slug/favorite', async (req) => {
    const userId = requireUserId(req);
    const article = await loadArticle((req.params as { slug: string }).slug, userId);
    await prisma.favorite.deleteMany({ where: { userId, articleId: article.id } });
    return { article: articleJson(await loadArticle(article.slug, userId), true) };
  });

  // ---------- comments ----------

  const commentJson = (
    c: { id: number; body: string; createdAt: Date; updatedAt: Date; author: UserRow & { followers?: unknown[] } },
  ) => ({
    id: c.id,
    createdAt: c.createdAt.toISOString(),
    updatedAt: c.updatedAt.toISOString(),
    body: c.body,
    author: profileJson(c.author, (c.author.followers?.length ?? 0) > 0),
  });

  const commentInclude = (viewerId: number | null) => ({
    author: { include: { followers: viewerId ? { where: { followerId: viewerId } } : false } },
  }) satisfies Prisma.CommentInclude;

  async function articleIdBySlug(slug: string): Promise<number> {
    const a = await prisma.article.findUnique({ where: { slug }, select: { id: true } });
    if (!a) throw notFound('article');
    return a.id;
  }

  app.get('/api/articles/:slug/comments', async (req) => {
    const viewerId = userIdFromRequest(req);
    const articleId = await articleIdBySlug((req.params as { slug: string }).slug);
    const comments = await prisma.comment.findMany({
      where: { articleId },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      include: commentInclude(viewerId),
    });
    return { comments: comments.map((c) => commentJson(c as never)) };
  });

  app.post('/api/articles/:slug/comments', async (req, reply) => {
    const userId = requireUserId(req);
    const input = wrapped(req, 'comment');
    const articleId = await articleIdBySlug((req.params as { slug: string }).slug);
    if (isBlank(input.body)) throw unprocessable({ body: ["can't be blank"] });
    const comment = await prisma.comment.create({
      data: { body: input.body as string, articleId, authorId: userId },
      include: commentInclude(userId),
    });
    return reply.code(201).send({ comment: commentJson(comment as never) });
  });

  app.delete('/api/articles/:slug/comments/:id', async (req, reply) => {
    const userId = requireUserId(req);
    const params = req.params as { slug: string; id: string };
    const articleId = await articleIdBySlug(params.slug);
    const id = /^\d+$/.test(params.id) ? Number(params.id) : -1;
    const comment = await prisma.comment.findFirst({ where: { id, articleId } });
    if (!comment) throw notFound('comment');
    if (comment.authorId !== userId) throw forbidden('comment');
    await prisma.comment.delete({ where: { id } });
    return reply.code(204).send();
  });

  // ---------- tags ----------

  app.get('/api/tags', async () => {
    const tags = await prisma.tag.findMany({
      where: { articles: { some: {} } },
      orderBy: { name: 'asc' },
      select: { name: true },
    });
    return { tags: tags.map((t) => t.name) };
  });

  app.get('/health', async () => ({ status: 'ok' }));

  return app;
}

function uniqueViolation(e: unknown): unknown {
  if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
    const target = String((e.meta as { target?: unknown } | undefined)?.target ?? '');
    const errors: Errors = {};
    if (target.includes('email')) errors.email = ['has already been taken'];
    if (target.includes('username')) errors.username = ['has already been taken'];
    if (Object.keys(errors).length === 0) errors.user = ['has already been taken'];
    return conflict(errors);
  }
  return e;
}
