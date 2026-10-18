import crypto from 'node:crypto';
import Fastify, { FastifyInstance, FastifyRequest } from 'fastify';
import { Prisma, PrismaClient } from '@prisma/client';
import { hashPassword, optionalUserId, requireUserId, signToken, verifyPassword } from './auth';
import { HttpError, forbidden, invalid, notFound } from './errors';

type UserRow = { id: number; email: string; username: string; bio: string | null; image: string | null };

const articleInclude = {
  author: true,
  tags: { orderBy: { position: 'asc' } },
  _count: { select: { favorites: true } },
} satisfies Prisma.ArticleInclude;
type ArticleRow = Prisma.ArticleGetPayload<{ include: typeof articleInclude }>;

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isBlank = (v: unknown) => typeof v !== 'string' || v.trim() === '';

/** Extracts `{ [key]: {...} }` from the request body or fails with 422. */
function envelope(req: FastifyRequest, key: string): Record<string, unknown> {
  const body = req.body;
  if (!isObj(body) || !isObj(body[key])) throw invalid(key, "can't be blank");
  return body[key] as Record<string, unknown>;
}

/** Required string field: missing, null, non-string and empty values all count as blank. */
function requiredString(obj: Record<string, unknown>, field: string): string {
  const v = obj[field];
  if (isBlank(v)) throw invalid(field, "can't be blank");
  return v as string;
}

/** Optional string field for partial updates: absent => undefined, present => must be non-blank. */
function optionalRequiredString(obj: Record<string, unknown>, field: string): string | undefined {
  return field in obj ? requiredString(obj, field) : undefined;
}

/** Nullable text field: empty string and null both normalise to null. */
function nullableString(obj: Record<string, unknown>, field: string): string | null | undefined {
  if (!(field in obj)) return undefined;
  const v = obj[field];
  if (v === null || v === '') return null;
  if (typeof v !== 'string') throw invalid(field, 'is invalid');
  return v;
}

function checkPassword(pw: unknown): string {
  if (isBlank(pw)) throw invalid('password', "can't be blank");
  if ((pw as string).length < 8) throw invalid('password', 'is too short (minimum is 8 characters)');
  return pw as string;
}

function parseTags(v: unknown): string[] {
  if (!Array.isArray(v) || v.some((t) => typeof t !== 'string' || t.trim() === '')) {
    throw invalid('tagList', 'is invalid');
  }
  return [...new Set((v as string[]).map((t) => t.trim()))];
}

function slugify(title: string): string {
  const base = title.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80);
  return `${base || 'article'}-${crypto.randomBytes(4).toString('hex')}`;
}

function intParam(v: unknown, def: number, max: number): number {
  const n = typeof v === 'string' && v !== '' ? Number(v) : def;
  if (!Number.isInteger(n) || n < 0) return def;
  return Math.min(n, max);
}

const userView = (u: UserRow) => ({
  user: { email: u.email, username: u.username, bio: u.bio, image: u.image, token: signToken(u.id) },
});

const profileOf = (u: UserRow, following: boolean) => ({
  username: u.username,
  bio: u.bio,
  image: u.image,
  following,
});

export function buildApp(prisma: PrismaClient): FastifyInstance {
  const app = Fastify({ logger: false, bodyLimit: 1024 * 1024 });

  app.setErrorHandler((err: any, _req, reply) => {
    if (err instanceof HttpError) return reply.status(err.status).send({ errors: err.errors });
    if (err.code === 'FST_ERR_CTP_INVALID_JSON_BODY' || err.statusCode === 400 || err.code?.startsWith('FST_ERR_CTP')) {
      return reply.status(422).send({ errors: { body: ['is invalid'] } });
    }
    if (err.code === 'P2002') return reply.status(409).send({ errors: { resource: ['has already been taken'] } });
    app.log.error(err);
    return reply.status(500).send({ errors: { server: ['internal error'] } });
  });
  app.setNotFoundHandler((_req, reply) => reply.status(404).send({ errors: { route: ['not found'] } }));

  // ---- helpers bound to prisma ----

  async function currentUser(req: FastifyRequest): Promise<UserRow> {
    const id = requireUserId(req.headers.authorization);
    const user = await prisma.user.findUnique({ where: { id } });
    if (!user) throw new HttpError(401, { token: ['is invalid'] });
    return user;
  }

  async function articleViews(rows: ArticleRow[], viewerId: number | null) {
    let favorited = new Set<number>();
    let following = new Set<number>();
    if (viewerId !== null && rows.length > 0) {
      const favs = await prisma.favorite.findMany({
        where: { userId: viewerId, articleId: { in: rows.map((r) => r.id) } },
        select: { articleId: true },
      });
      favorited = new Set(favs.map((f) => f.articleId));
      const fol = await prisma.follow.findMany({
        where: { followerId: viewerId, followedId: { in: [...new Set(rows.map((r) => r.authorId))] } },
        select: { followedId: true },
      });
      following = new Set(fol.map((f) => f.followedId));
    }
    return rows.map((a) => ({
      slug: a.slug,
      title: a.title,
      description: a.description,
      body: a.body,
      tagList: a.tags.map((t) => t.name),
      createdAt: a.createdAt.toISOString(),
      updatedAt: a.updatedAt.toISOString(),
      favorited: favorited.has(a.id),
      favoritesCount: a._count.favorites,
      author: profileOf(a.author, following.has(a.authorId)),
    }));
  }

  async function singleArticle(slug: string, viewerId: number | null) {
    const row = await prisma.article.findUnique({ where: { slug }, include: articleInclude });
    if (!row) throw notFound('article');
    return { row, view: (await articleViews([row], viewerId))[0] };
  }

  async function listArticles(where: Prisma.ArticleWhereInput, q: Record<string, unknown>, viewerId: number | null) {
    const limit = intParam(q.limit, 20, 100);
    const offset = intParam(q.offset, 0, Number.MAX_SAFE_INTEGER);
    const [rows, count] = await Promise.all([
      prisma.article.findMany({
        where,
        include: articleInclude,
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        take: limit,
        skip: offset,
      }),
      prisma.article.count({ where }),
    ]);
    const views = (await articleViews(rows, viewerId)).map(({ body: _body, ...rest }) => rest);
    return { articles: views, articlesCount: count };
  }

  // ---- users ----

  app.post('/api/users', async (req, reply) => {
    const u = envelope(req, 'user');
    const username = requiredString(u, 'username');
    const email = requiredString(u, 'email');
    const password = checkPassword(u.password);
    const errors: Record<string, string[]> = {};
    if (await prisma.user.findUnique({ where: { username } })) errors.username = ['has already been taken'];
    if (await prisma.user.findUnique({ where: { email } })) errors.email = ['has already been taken'];
    if (Object.keys(errors).length) throw new HttpError(409, errors);
    const user = await prisma.user.create({ data: { username, email, passwordHash: await hashPassword(password) } });
    return reply.status(201).send(userView(user));
  });

  app.post('/api/users/login', async (req) => {
    const u = envelope(req, 'user');
    const email = requiredString(u, 'email');
    const password = requiredString(u, 'password');
    const user = await prisma.user.findUnique({ where: { email } });
    if (!user || !(await verifyPassword(password, user.passwordHash))) {
      throw new HttpError(401, { credentials: ['invalid'] });
    }
    return userView(user);
  });

  app.get('/api/user', async (req) => userView(await currentUser(req)));

  app.put('/api/user', async (req) => {
    const me = await currentUser(req);
    const u = envelope(req, 'user');
    const data: Prisma.UserUpdateInput = {};
    const username = optionalRequiredString(u, 'username');
    const email = optionalRequiredString(u, 'email');
    if (username !== undefined) data.username = username;
    if (email !== undefined) data.email = email;
    if ('password' in u) data.passwordHash = await hashPassword(checkPassword(u.password));
    const bio = nullableString(u, 'bio');
    const image = nullableString(u, 'image');
    if (bio !== undefined) data.bio = bio;
    if (image !== undefined) data.image = image;

    const errors: Record<string, string[]> = {};
    if (username !== undefined && username !== me.username && (await prisma.user.findUnique({ where: { username } }))) {
      errors.username = ['has already been taken'];
    }
    if (email !== undefined && email !== me.email && (await prisma.user.findUnique({ where: { email } }))) {
      errors.email = ['has already been taken'];
    }
    if (Object.keys(errors).length) throw new HttpError(409, errors);
    return userView(await prisma.user.update({ where: { id: me.id }, data }));
  });

  // ---- profiles ----

  async function profileResponse(username: string, viewerId: number | null) {
    const user = await prisma.user.findUnique({ where: { username } });
    if (!user) throw notFound('profile');
    const following =
      viewerId !== null &&
      !!(await prisma.follow.findUnique({ where: { followerId_followedId: { followerId: viewerId, followedId: user.id } } }));
    return { user, following };
  }

  app.get('/api/profiles/:username', async (req) => {
    const { username } = req.params as { username: string };
    const { user, following } = await profileResponse(username, optionalUserId(req.headers.authorization));
    return { profile: profileOf(user, following) };
  });

  app.post('/api/profiles/:username/follow', async (req) => {
    const me = await currentUser(req);
    const { username } = req.params as { username: string };
    const target = await prisma.user.findUnique({ where: { username } });
    if (!target) throw notFound('profile');
    await prisma.follow.upsert({
      where: { followerId_followedId: { followerId: me.id, followedId: target.id } },
      create: { followerId: me.id, followedId: target.id },
      update: {},
    });
    return { profile: profileOf(target, true) };
  });

  app.delete('/api/profiles/:username/follow', async (req) => {
    const me = await currentUser(req);
    const { username } = req.params as { username: string };
    const target = await prisma.user.findUnique({ where: { username } });
    if (!target) throw notFound('profile');
    await prisma.follow.deleteMany({ where: { followerId: me.id, followedId: target.id } });
    return { profile: profileOf(target, false) };
  });

  // ---- articles ----

  app.get('/api/articles/feed', async (req) => {
    const me = await currentUser(req);
    const where: Prisma.ArticleWhereInput = { author: { followers: { some: { followerId: me.id } } } };
    return listArticles(where, req.query as Record<string, unknown>, me.id);
  });

  app.get('/api/articles', async (req) => {
    const q = req.query as Record<string, unknown>;
    const where: Prisma.ArticleWhereInput = {};
    if (typeof q.tag === 'string' && q.tag) where.tags = { some: { name: q.tag } };
    if (typeof q.author === 'string' && q.author) where.author = { username: q.author };
    if (typeof q.favorited === 'string' && q.favorited) where.favorites = { some: { user: { username: q.favorited } } };
    return listArticles(where, q, optionalUserId(req.headers.authorization));
  });

  app.post('/api/articles', async (req, reply) => {
    const me = await currentUser(req);
    const a = envelope(req, 'article');
    const title = requiredString(a, 'title');
    const description = requiredString(a, 'description');
    const body = requiredString(a, 'body');
    const tags = a.tagList === undefined || a.tagList === null ? [] : parseTags(a.tagList);
    const created = await prisma.article.create({
      data: {
        slug: slugify(title),
        title,
        description,
        body,
        authorId: me.id,
        tags: { create: tags.map((name, position) => ({ name, position })) },
      },
      include: articleInclude,
    });
    return reply.status(201).send({ article: (await articleViews([created], me.id))[0] });
  });

  app.get('/api/articles/:slug', async (req) => {
    const { slug } = req.params as { slug: string };
    const { view } = await singleArticle(slug, optionalUserId(req.headers.authorization));
    return { article: view };
  });

  app.put('/api/articles/:slug', async (req) => {
    const me = await currentUser(req);
    const { slug } = req.params as { slug: string };
    const existing = await prisma.article.findUnique({ where: { slug } });
    if (!existing) throw notFound('article');
    if (existing.authorId !== me.id) throw forbidden('article');
    const a = envelope(req, 'article');
    const title = optionalRequiredString(a, 'title');
    const description = optionalRequiredString(a, 'description');
    const body = optionalRequiredString(a, 'body');
    const tags = 'tagList' in a ? parseTags(a.tagList) : undefined;
    const updated = await prisma.$transaction(async (tx) => {
      if (tags !== undefined) {
        await tx.articleTag.deleteMany({ where: { articleId: existing.id } });
        await tx.articleTag.createMany({ data: tags.map((name, position) => ({ articleId: existing.id, name, position })) });
      }
      return tx.article.update({
        where: { id: existing.id },
        data: {
          ...(title !== undefined && title !== existing.title ? { title, slug: slugify(title) } : {}),
          ...(description !== undefined ? { description } : {}),
          ...(body !== undefined ? { body } : {}),
          updatedAt: new Date(),
        },
        include: articleInclude,
      });
    });
    return { article: (await articleViews([updated], me.id))[0] };
  });

  app.delete('/api/articles/:slug', async (req, reply) => {
    const me = await currentUser(req);
    const { slug } = req.params as { slug: string };
    const existing = await prisma.article.findUnique({ where: { slug } });
    if (!existing) throw notFound('article');
    if (existing.authorId !== me.id) throw forbidden('article');
    await prisma.article.delete({ where: { id: existing.id } });
    return reply.status(204).send();
  });

  // ---- favorites ----

  app.post('/api/articles/:slug/favorite', async (req) => {
    const me = await currentUser(req);
    const { slug } = req.params as { slug: string };
    const article = await prisma.article.findUnique({ where: { slug } });
    if (!article) throw notFound('article');
    await prisma.favorite.upsert({
      where: { userId_articleId: { userId: me.id, articleId: article.id } },
      create: { userId: me.id, articleId: article.id },
      update: {},
    });
    return { article: (await singleArticle(slug, me.id)).view };
  });

  app.delete('/api/articles/:slug/favorite', async (req) => {
    const me = await currentUser(req);
    const { slug } = req.params as { slug: string };
    const article = await prisma.article.findUnique({ where: { slug } });
    if (!article) throw notFound('article');
    await prisma.favorite.deleteMany({ where: { userId: me.id, articleId: article.id } });
    return { article: (await singleArticle(slug, me.id)).view };
  });

  // ---- comments ----

  const commentView = (c: Prisma.CommentGetPayload<{ include: { author: true } }>, following: boolean) => ({
    id: c.id,
    body: c.body,
    createdAt: c.createdAt.toISOString(),
    updatedAt: c.updatedAt.toISOString(),
    author: profileOf(c.author, following),
  });

  app.get('/api/articles/:slug/comments', async (req) => {
    const { slug } = req.params as { slug: string };
    const viewerId = optionalUserId(req.headers.authorization);
    const article = await prisma.article.findUnique({ where: { slug } });
    if (!article) throw notFound('article');
    const rows = await prisma.comment.findMany({
      where: { articleId: article.id },
      include: { author: true },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    });
    let following = new Set<number>();
    if (viewerId !== null && rows.length) {
      const fol = await prisma.follow.findMany({
        where: { followerId: viewerId, followedId: { in: [...new Set(rows.map((r) => r.authorId))] } },
      });
      following = new Set(fol.map((f) => f.followedId));
    }
    return { comments: rows.map((c) => commentView(c, following.has(c.authorId))) };
  });

  app.post('/api/articles/:slug/comments', async (req, reply) => {
    const me = await currentUser(req);
    const { slug } = req.params as { slug: string };
    const article = await prisma.article.findUnique({ where: { slug } });
    if (!article) throw notFound('article');
    const body = requiredString(envelope(req, 'comment'), 'body');
    const c = await prisma.comment.create({
      data: { body, articleId: article.id, authorId: me.id },
      include: { author: true },
    });
    return reply.status(201).send({ comment: commentView(c, false) });
  });

  app.delete('/api/articles/:slug/comments/:id', async (req, reply) => {
    const me = await currentUser(req);
    const { slug, id } = req.params as { slug: string; id: string };
    const article = await prisma.article.findUnique({ where: { slug } });
    if (!article) throw notFound('article');
    const commentId = /^\d+$/.test(id) ? Number(id) : NaN;
    const comment = Number.isSafeInteger(commentId)
      ? await prisma.comment.findFirst({ where: { id: commentId, articleId: article.id } })
      : null;
    if (!comment) throw notFound('comment');
    if (comment.authorId !== me.id) throw forbidden('comment');
    await prisma.comment.delete({ where: { id: comment.id } });
    return reply.status(204).send();
  });

  // ---- tags ----

  app.get('/api/tags', async () => {
    const rows = await prisma.articleTag.findMany({ distinct: ['name'], select: { name: true }, orderBy: { name: 'asc' } });
    return { tags: rows.map((r) => r.name) };
  });

  return app;
}
