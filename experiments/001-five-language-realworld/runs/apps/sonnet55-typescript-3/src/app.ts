import Fastify, { FastifyInstance, FastifyRequest } from 'fastify';
import bcrypt from 'bcryptjs';
import { randomBytes } from 'node:crypto';
import { Prisma } from '@prisma/client';
import { prisma } from './db';
import { optionalUserId, requireUserId, signToken } from './auth';
import { HttpError, invalid, isBlank, notFound, forbidden, ErrorBody } from './errors';

type Body = Record<string, any>;

const articleInclude = {
  author: true,
  tags: { include: { tag: true } },
  _count: { select: { favorites: true } },
} satisfies Prisma.ArticleInclude;
type ArticleRow = Prisma.ArticleGetPayload<{ include: typeof articleInclude }>;

function wrapped(req: FastifyRequest, key: string): Body {
  const body = req.body as Body | undefined;
  const inner = body && typeof body === 'object' ? body[key] : undefined;
  return inner && typeof inner === 'object' ? inner : {};
}

function userJson(u: { email: string; username: string; bio: string | null; image: string | null }) {
  return { user: { email: u.email, token: signToken((u as any).id), username: u.username, bio: u.bio, image: u.image } };
}

function profileJson(u: { username: string; bio: string | null; image: string | null }, following: boolean) {
  return { username: u.username, bio: u.bio, image: u.image, following };
}

async function followingSet(viewerId: number | null, authorIds: number[]): Promise<Set<number>> {
  if (viewerId === null || authorIds.length === 0) return new Set();
  const rows = await prisma.follow.findMany({
    where: { followerId: viewerId, followeeId: { in: authorIds } },
    select: { followeeId: true },
  });
  return new Set(rows.map((r) => r.followeeId));
}

async function favoritedSet(viewerId: number | null, articleIds: number[]): Promise<Set<number>> {
  if (viewerId === null || articleIds.length === 0) return new Set();
  const rows = await prisma.favorite.findMany({
    where: { userId: viewerId, articleId: { in: articleIds } },
    select: { articleId: true },
  });
  return new Set(rows.map((r) => r.articleId));
}

async function articlesJson(rows: ArticleRow[], viewerId: number | null, withBody = true) {
  const [following, favorited] = await Promise.all([
    followingSet(viewerId, [...new Set(rows.map((r) => r.authorId))]),
    favoritedSet(viewerId, rows.map((r) => r.id)),
  ]);
  return rows.map((a) => ({
    slug: a.slug,
    title: a.title,
    description: a.description,
    ...(withBody ? { body: a.body } : {}),
    tagList: a.tags.map((t) => t.tag.name).sort(),
    createdAt: a.createdAt.toISOString(),
    updatedAt: a.updatedAt.toISOString(),
    favorited: favorited.has(a.id),
    favoritesCount: a._count.favorites,
    author: profileJson(a.author, following.has(a.authorId)),
  }));
}

async function loadArticle(slug: string): Promise<ArticleRow> {
  const a = await prisma.article.findUnique({ where: { slug }, include: articleInclude });
  if (!a) throw notFound('article');
  return a;
}

function slugify(title: string): string {
  const base = title
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80);
  return `${base || 'article'}-${randomBytes(4).toString('hex')}`;
}

function parseTagList(v: unknown): string[] {
  if (!Array.isArray(v) || v.some((t) => typeof t !== 'string')) {
    throw invalid({ tagList: ['is invalid'] });
  }
  return [...new Set((v as string[]).map((t) => t.trim()).filter(Boolean))];
}

function tagConnect(names: string[]) {
  return names.map((name) => ({
    tag: { connectOrCreate: { where: { name }, create: { name } } },
  }));
}

function parseCount(v: unknown, def: number, max?: number): number {
  const n = Number.parseInt(String(v ?? ''), 10);
  if (!Number.isFinite(n) || n < 0) return def;
  return max ? Math.min(n, max) : n;
}

const isUniqueViolation = (e: unknown) =>
  e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002';

export function buildApp(): FastifyInstance {
  const app = Fastify({ logger: false, bodyLimit: 2 * 1024 * 1024 });

  app.setErrorHandler((err: any, _req, reply) => {
    if (err instanceof HttpError) return reply.status(err.status).send({ errors: err.errors });
    if (err.statusCode && err.statusCode < 500) {
      return reply.status(422).send({ errors: { body: [err.message] } });
    }
    console.error(err);
    return reply.status(500).send({ errors: { server: ['internal error'] } });
  });
  app.setNotFoundHandler((_req, reply) => reply.status(404).send({ errors: { route: ['not found'] } }));

  // ---------- users ----------
  app.post('/api/users', async (req, reply) => {
    const b = wrapped(req, 'user');
    const errors: ErrorBody = {};
    if (isBlank(b.username)) errors.username = ["can't be blank"];
    if (isBlank(b.email)) errors.email = ["can't be blank"];
    else if (!/^[^@\s]+@[^@\s]+$/.test(b.email)) errors.email = ['is invalid'];
    if (isBlank(b.password)) errors.password = ["can't be blank"];
    else if (b.password.length < 8) errors.password = ['is too short (minimum is 8 characters)'];
    if (Object.keys(errors).length) throw invalid(errors);

    const [byName, byEmail] = await Promise.all([
      prisma.user.findUnique({ where: { username: b.username } }),
      prisma.user.findUnique({ where: { email: b.email } }),
    ]);
    const conflicts: ErrorBody = {};
    if (byName) conflicts.username = ['has already been taken'];
    if (byEmail) conflicts.email = ['has already been taken'];
    if (Object.keys(conflicts).length) throw new HttpError(409, conflicts);

    try {
      const user = await prisma.user.create({
        data: { username: b.username, email: b.email, password: await bcrypt.hash(b.password, 10) },
      });
      return reply.status(201).send(userJson(user));
    } catch (e) {
      if (isUniqueViolation(e)) throw new HttpError(409, { username: ['has already been taken'] });
      throw e;
    }
  });

  app.post('/api/users/login', async (req) => {
    const b = wrapped(req, 'user');
    const errors: ErrorBody = {};
    if (isBlank(b.email)) errors.email = ["can't be blank"];
    if (isBlank(b.password)) errors.password = ["can't be blank"];
    if (Object.keys(errors).length) throw invalid(errors);
    const user = await prisma.user.findUnique({ where: { email: b.email } });
    if (!user || !(await bcrypt.compare(b.password, user.password))) {
      throw new HttpError(401, { credentials: ['invalid'] });
    }
    return userJson(user);
  });

  const currentUser = async (req: FastifyRequest) => {
    const user = await prisma.user.findUnique({ where: { id: requireUserId(req) } });
    if (!user) throw new HttpError(401, { token: ['is invalid'] });
    return user;
  };

  app.get('/api/user', async (req) => userJson(await currentUser(req)));

  app.put('/api/user', async (req) => {
    const me = await currentUser(req);
    const b = wrapped(req, 'user');
    const errors: ErrorBody = {};
    const data: Prisma.UserUpdateInput = {};
    if ('username' in b) {
      if (isBlank(b.username)) errors.username = ["can't be blank"];
      else data.username = b.username;
    }
    if ('email' in b) {
      if (isBlank(b.email)) errors.email = ["can't be blank"];
      else if (!/^[^@\s]+@[^@\s]+$/.test(b.email)) errors.email = ['is invalid'];
      else data.email = b.email;
    }
    if ('password' in b) {
      if (isBlank(b.password)) errors.password = ["can't be blank"];
      else if (b.password.length < 8) errors.password = ['is too short (minimum is 8 characters)'];
      else data.password = await bcrypt.hash(b.password, 10);
    }
    for (const f of ['bio', 'image'] as const) {
      if (f in b) {
        if (b[f] !== null && typeof b[f] !== 'string') errors[f] = ['is invalid'];
        else data[f] = b[f] === null || b[f] === '' ? null : b[f];
      }
    }
    if (Object.keys(errors).length) throw invalid(errors);

    const conflicts: ErrorBody = {};
    if (typeof data.username === 'string' && data.username !== me.username &&
        (await prisma.user.findUnique({ where: { username: data.username } }))) {
      conflicts.username = ['has already been taken'];
    }
    if (typeof data.email === 'string' && data.email !== me.email &&
        (await prisma.user.findUnique({ where: { email: data.email } }))) {
      conflicts.email = ['has already been taken'];
    }
    if (Object.keys(conflicts).length) throw new HttpError(409, conflicts);

    try {
      return userJson(await prisma.user.update({ where: { id: me.id }, data }));
    } catch (e) {
      if (isUniqueViolation(e)) throw new HttpError(409, { username: ['has already been taken'] });
      throw e;
    }
  });

  // ---------- profiles ----------
  const findProfile = async (username: string) => {
    const u = await prisma.user.findUnique({ where: { username } });
    if (!u) throw notFound('profile');
    return u;
  };

  app.get('/api/profiles/:username', async (req) => {
    const viewer = optionalUserId(req);
    const u = await findProfile((req.params as any).username);
    const following = await followingSet(viewer, [u.id]);
    return { profile: profileJson(u, following.has(u.id)) };
  });

  app.post('/api/profiles/:username/follow', async (req) => {
    const me = requireUserId(req);
    const u = await findProfile((req.params as any).username);
    await prisma.follow.upsert({
      where: { followerId_followeeId: { followerId: me, followeeId: u.id } },
      create: { followerId: me, followeeId: u.id },
      update: {},
    });
    return { profile: profileJson(u, true) };
  });

  app.delete('/api/profiles/:username/follow', async (req) => {
    const me = requireUserId(req);
    const u = await findProfile((req.params as any).username);
    await prisma.follow.deleteMany({ where: { followerId: me, followeeId: u.id } });
    return { profile: profileJson(u, false) };
  });

  // ---------- articles ----------
  app.get('/api/articles', async (req) => {
    const viewer = optionalUserId(req);
    const q = req.query as Record<string, string | undefined>;
    const where: Prisma.ArticleWhereInput = {};
    if (q.tag) where.tags = { some: { tag: { name: q.tag } } };
    if (q.author) where.author = { username: q.author };
    if (q.favorited) where.favorites = { some: { user: { username: q.favorited } } };
    const [rows, count] = await Promise.all([
      prisma.article.findMany({
        where,
        include: articleInclude,
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        take: parseCount(q.limit, 20, 100),
        skip: parseCount(q.offset, 0),
      }),
      prisma.article.count({ where }),
    ]);
    return { articles: await articlesJson(rows, viewer, false), articlesCount: count };
  });

  // Registered before /:slug so "feed" is not treated as a slug.
  app.get('/api/articles/feed', async (req) => {
    const me = requireUserId(req);
    const q = req.query as Record<string, string | undefined>;
    const where: Prisma.ArticleWhereInput = { author: { followers: { some: { followerId: me } } } };
    const [rows, count] = await Promise.all([
      prisma.article.findMany({
        where,
        include: articleInclude,
        orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
        take: parseCount(q.limit, 20, 100),
        skip: parseCount(q.offset, 0),
      }),
      prisma.article.count({ where }),
    ]);
    return { articles: await articlesJson(rows, me, false), articlesCount: count };
  });

  app.get('/api/articles/:slug', async (req) => {
    const viewer = optionalUserId(req);
    const a = await loadArticle((req.params as any).slug);
    return { article: (await articlesJson([a], viewer))[0] };
  });

  app.post('/api/articles', async (req, reply) => {
    const me = requireUserId(req);
    const b = wrapped(req, 'article');
    const errors: ErrorBody = {};
    for (const f of ['title', 'description', 'body']) if (isBlank(b[f])) errors[f] = ["can't be blank"];
    let tags: string[] = [];
    if (b.tagList !== undefined) {
      try { tags = parseTagList(b.tagList); } catch (e) { errors.tagList = ['is invalid']; }
    }
    if (Object.keys(errors).length) throw invalid(errors);
    const created = await prisma.article.create({
      data: {
        slug: slugify(b.title),
        title: b.title,
        description: b.description,
        body: b.body,
        authorId: me,
        tags: { create: tagConnect(tags) },
      },
      include: articleInclude,
    });
    return reply.status(201).send({ article: (await articlesJson([created], me))[0] });
  });

  app.put('/api/articles/:slug', async (req) => {
    const me = requireUserId(req);
    const existing = await loadArticle((req.params as any).slug);
    if (existing.authorId !== me) throw forbidden('article');
    const b = wrapped(req, 'article');
    const errors: ErrorBody = {};
    const data: Prisma.ArticleUpdateInput = {};
    for (const f of ['title', 'description', 'body'] as const) {
      if (f in b) {
        if (isBlank(b[f])) errors[f] = ["can't be blank"];
        else data[f] = b[f];
      }
    }
    let tags: string[] | null = null;
    if ('tagList' in b) {
      try { tags = parseTagList(b.tagList); } catch { errors.tagList = ['is invalid']; }
    }
    if (Object.keys(errors).length) throw invalid(errors);
    if (tags) {
      data.tags = { deleteMany: {}, create: tagConnect(tags) };
    }
    // Touch updatedAt explicitly so it changes even when only tags are replaced.
    data.updatedAt = new Date();
    const updated = await prisma.article.update({
      where: { id: existing.id },
      data,
      include: articleInclude,
    });
    return { article: (await articlesJson([updated], me))[0] };
  });

  app.delete('/api/articles/:slug', async (req, reply) => {
    const me = requireUserId(req);
    const existing = await loadArticle((req.params as any).slug);
    if (existing.authorId !== me) throw forbidden('article');
    await prisma.article.delete({ where: { id: existing.id } });
    return reply.status(204).send();
  });

  // ---------- favorites ----------
  app.post('/api/articles/:slug/favorite', async (req) => {
    const me = requireUserId(req);
    const a = await loadArticle((req.params as any).slug);
    await prisma.favorite.upsert({
      where: { userId_articleId: { userId: me, articleId: a.id } },
      create: { userId: me, articleId: a.id },
      update: {},
    });
    return { article: (await articlesJson([await loadArticle(a.slug)], me))[0] };
  });

  app.delete('/api/articles/:slug/favorite', async (req) => {
    const me = requireUserId(req);
    const a = await loadArticle((req.params as any).slug);
    await prisma.favorite.deleteMany({ where: { userId: me, articleId: a.id } });
    return { article: (await articlesJson([await loadArticle(a.slug)], me))[0] };
  });

  // ---------- comments ----------
  const commentJson = async (
    c: Prisma.CommentGetPayload<{ include: { author: true } }>,
    viewer: number | null,
  ) => {
    const following = await followingSet(viewer, [c.authorId]);
    return {
      id: c.id,
      createdAt: c.createdAt.toISOString(),
      updatedAt: c.updatedAt.toISOString(),
      body: c.body,
      author: profileJson(c.author, following.has(c.authorId)),
    };
  };

  app.get('/api/articles/:slug/comments', async (req) => {
    const viewer = optionalUserId(req);
    const a = await loadArticle((req.params as any).slug);
    const rows = await prisma.comment.findMany({
      where: { articleId: a.id },
      include: { author: true },
      orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
    });
    const following = await followingSet(viewer, [...new Set(rows.map((r) => r.authorId))]);
    return {
      comments: rows.map((c) => ({
        id: c.id,
        createdAt: c.createdAt.toISOString(),
        updatedAt: c.updatedAt.toISOString(),
        body: c.body,
        author: profileJson(c.author, following.has(c.authorId)),
      })),
    };
  });

  app.post('/api/articles/:slug/comments', async (req, reply) => {
    const me = requireUserId(req);
    const a = await loadArticle((req.params as any).slug);
    const b = wrapped(req, 'comment');
    if (isBlank(b.body)) throw invalid({ body: ["can't be blank"] });
    const c = await prisma.comment.create({
      data: { body: b.body, articleId: a.id, authorId: me },
      include: { author: true },
    });
    return reply.status(201).send({ comment: await commentJson(c, me) });
  });

  app.delete('/api/articles/:slug/comments/:id', async (req, reply) => {
    const me = requireUserId(req);
    const { slug, id } = req.params as { slug: string; id: string };
    const a = await loadArticle(slug);
    const cid = /^\d+$/.test(id) ? Number(id) : NaN;
    const c = Number.isSafeInteger(cid)
      ? await prisma.comment.findFirst({ where: { id: cid, articleId: a.id } })
      : null;
    if (!c) throw notFound('comment');
    if (c.authorId !== me) throw forbidden('comment');
    await prisma.comment.delete({ where: { id: c.id } });
    return reply.status(204).send();
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

  return app;
}
