import Fastify, { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { randomBytes } from 'crypto';
import { Prisma } from '@prisma/client';
import { prisma } from './db';

const JWT_SECRET = process.env.JWT_SECRET || 'realworld-dev-secret-change-me';
const MIN_PASSWORD = 8;

type Errors = Record<string, string[]>;

class HttpError extends Error {
  constructor(public status: number, public errors: Errors) {
    super('http error');
  }
}

const fail = (status: number, field: string, msg: string): never => {
  throw new HttpError(status, { [field]: [msg] });
};

const isObj = (v: unknown): v is Record<string, any> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const blank = (v: unknown) => typeof v !== 'string' || v.trim() === '';

function signToken(userId: number): string {
  return jwt.sign({ id: userId }, JWT_SECRET, { expiresIn: '30d' });
}

interface AuthUser {
  id: number;
  email: string;
  username: string;
  bio: string | null;
  image: string | null;
}

declare module 'fastify' {
  interface FastifyRequest {
    user?: AuthUser;
    token?: string;
  }
}

async function authenticate(req: FastifyRequest, required: boolean): Promise<void> {
  const header = req.headers.authorization;
  if (!header) {
    if (required) fail(401, 'token', 'is missing');
    return;
  }
  const m = /^(?:Token|Bearer)\s+(.+)$/i.exec(header);
  if (!m) {
    if (required) fail(401, 'token', 'is missing');
    return;
  }
  let id: number;
  try {
    const payload = jwt.verify(m[1], JWT_SECRET) as { id: number };
    id = payload.id;
  } catch {
    if (required) fail(401, 'token', 'is invalid');
    return;
  }
  const user = await prisma.user.findUnique({ where: { id } });
  if (!user) {
    if (required) fail(401, 'token', 'is invalid');
    return;
  }
  req.user = user;
  req.token = m[1];
}

const requireAuth = async (req: FastifyRequest) => authenticate(req, true);
const optionalAuth = async (req: FastifyRequest) => authenticate(req, false);

function userJson(u: AuthUser, token: string) {
  return { user: { email: u.email, username: u.username, bio: u.bio, image: u.image, token } };
}

function slugify(title: string): string {
  const base = title
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return (base || 'article') + '-' + randomBytes(4).toString('hex');
}

const articleInclude = (viewerId?: number) =>
  ({
    author: {
      include: viewerId
        ? { followers: { where: { followerId: viewerId }, select: { followerId: true } } }
        : undefined,
    },
    tags: { include: { tag: true } },
    _count: { select: { favorites: true } },
    favorites: viewerId ? { where: { userId: viewerId }, select: { userId: true } } : false,
  }) as any;

function articleJson(a: any, withBody: boolean) {
  const out: any = {
    slug: a.slug,
    title: a.title,
    description: a.description,
  };
  if (withBody) out.body = a.body;
  out.tagList = a.tags.map((t: any) => t.tag.name).sort();
  out.createdAt = a.createdAt.toISOString();
  out.updatedAt = a.updatedAt.toISOString();
  out.favorited = Array.isArray(a.favorites) ? a.favorites.length > 0 : false;
  out.favoritesCount = a._count.favorites;
  out.author = {
    username: a.author.username,
    bio: a.author.bio,
    image: a.author.image,
    following: Array.isArray(a.author.followers) ? a.author.followers.length > 0 : false,
  };
  return out;
}

function commentJson(c: any) {
  return {
    id: c.id,
    createdAt: c.createdAt.toISOString(),
    updatedAt: c.updatedAt.toISOString(),
    body: c.body,
    author: {
      username: c.author.username,
      bio: c.author.bio,
      image: c.author.image,
      following: Array.isArray(c.author.followers) ? c.author.followers.length > 0 : false,
    },
  };
}

function profileJson(u: any, following: boolean) {
  return { profile: { username: u.username, bio: u.bio, image: u.image, following } };
}

function parseTags(v: unknown): string[] {
  if (!Array.isArray(v) || v.some((t) => typeof t !== 'string' || t.trim() === '')) {
    fail(422, 'tagList', 'is invalid');
  }
  return [...new Set((v as string[]).map((t) => t.trim()))];
}

function intParam(v: unknown, def: number, max?: number): number {
  const n = typeof v === 'string' ? parseInt(v, 10) : NaN;
  if (!Number.isFinite(n) || n < 0) return def;
  return max ? Math.min(n, max) : n;
}

async function findArticle(slug: string, viewerId?: number) {
  const a = await prisma.article.findUnique({ where: { slug }, include: articleInclude(viewerId) });
  if (!a) fail(404, 'article', 'not found');
  return a as any;
}

function nullable(v: unknown, field: string): string | null {
  if (v === null) return null;
  if (typeof v !== 'string') return fail(422, field, 'is invalid');
  return v === '' ? null : v;
}

export function buildApp(): FastifyInstance {
  const app = Fastify({ logger: false });

  app.setErrorHandler((err: any, _req, reply) => {
    if (err instanceof HttpError) return reply.code(err.status).send({ errors: err.errors });
    if (err.statusCode && err.statusCode < 500) {
      return reply.code(err.statusCode === 400 ? 422 : err.statusCode).send({ errors: { body: [err.message] } });
    }
    console.error(err);
    return reply.code(500).send({ errors: { server: ['internal error'] } });
  });
  app.setNotFoundHandler((_req, reply) => reply.code(404).send({ errors: { route: ['not found'] } }));

  // ---- Users & auth ----
  app.post('/api/users', async (req, reply) => {
    const u = isObj(req.body) && isObj(req.body.user) ? req.body.user : {};
    const errors: Errors = {};
    if (blank(u.username)) errors.username = ["can't be blank"];
    if (blank(u.email)) errors.email = ["can't be blank"];
    if (blank(u.password)) errors.password = ["can't be blank"];
    else if (u.password.length < MIN_PASSWORD) errors.password = ['is too short (minimum is 8 characters)'];
    if (Object.keys(errors).length) throw new HttpError(422, errors);

    const dup: Errors = {};
    if (await prisma.user.findUnique({ where: { username: u.username } })) dup.username = ['has already been taken'];
    if (await prisma.user.findUnique({ where: { email: u.email } })) dup.email = ['has already been taken'];
    if (Object.keys(dup).length) throw new HttpError(409, dup);

    const hash = await bcrypt.hash(u.password, 10);
    try {
      const created = await prisma.user.create({
        data: { username: u.username, email: u.email, password: hash },
      });
      return reply.code(201).send(userJson(created, signToken(created.id)));
    } catch (e) {
      if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
        const target = String((e.meta as any)?.target ?? '');
        throw new HttpError(409, { [target.includes('email') ? 'email' : 'username']: ['has already been taken'] });
      }
      throw e;
    }
  });

  app.post('/api/users/login', async (req, reply) => {
    const u = isObj(req.body) && isObj(req.body.user) ? req.body.user : {};
    const errors: Errors = {};
    if (blank(u.email)) errors.email = ["can't be blank"];
    if (blank(u.password)) errors.password = ["can't be blank"];
    if (Object.keys(errors).length) throw new HttpError(422, errors);
    const user = await prisma.user.findUnique({ where: { email: u.email } });
    if (!user || !(await bcrypt.compare(u.password, user.password))) fail(401, 'credentials', 'invalid');
    return reply.send(userJson(user!, signToken(user!.id)));
  });

  app.get('/api/user', { preHandler: requireAuth }, async (req) => userJson(req.user!, req.token!));

  app.put('/api/user', { preHandler: requireAuth }, async (req) => {
    const u = isObj(req.body) && isObj(req.body.user) ? req.body.user : {};
    const errors: Errors = {};
    const data: Prisma.UserUpdateInput = {};
    if ('username' in u) {
      if (blank(u.username)) errors.username = ["can't be blank"];
      else data.username = u.username;
    }
    if ('email' in u) {
      if (blank(u.email)) errors.email = ["can't be blank"];
      else data.email = u.email;
    }
    if ('password' in u) {
      if (blank(u.password)) errors.password = ["can't be blank"];
      else if (u.password.length < MIN_PASSWORD) errors.password = ['is too short (minimum is 8 characters)'];
      else data.password = await bcrypt.hash(u.password, 10);
    }
    if ('bio' in u) data.bio = nullable(u.bio, 'bio');
    if ('image' in u) data.image = nullable(u.image, 'image');
    if (Object.keys(errors).length) throw new HttpError(422, errors);

    const me = req.user!;
    const dup: Errors = {};
    if (typeof data.username === 'string' && data.username !== me.username &&
        (await prisma.user.findUnique({ where: { username: data.username } }))) dup.username = ['has already been taken'];
    if (typeof data.email === 'string' && data.email !== me.email &&
        (await prisma.user.findUnique({ where: { email: data.email } }))) dup.email = ['has already been taken'];
    if (Object.keys(dup).length) throw new HttpError(409, dup);

    const updated = await prisma.user.update({ where: { id: me.id }, data });
    return userJson(updated, signToken(updated.id));
  });

  // ---- Profiles ----
  async function profileOf(username: string, viewerId?: number) {
    const target = await prisma.user.findUnique({ where: { username } });
    if (!target) fail(404, 'profile', 'not found');
    const following = viewerId
      ? !!(await prisma.follow.findUnique({
          where: { followerId_followeeId: { followerId: viewerId, followeeId: target!.id } },
        }))
      : false;
    return { target: target!, following };
  }

  app.get('/api/profiles/:username', { preHandler: optionalAuth }, async (req) => {
    const { target, following } = await profileOf((req.params as any).username, req.user?.id);
    return profileJson(target, following);
  });

  app.post('/api/profiles/:username/follow', { preHandler: requireAuth }, async (req) => {
    const { target } = await profileOf((req.params as any).username);
    if (target.id !== req.user!.id) {
      await prisma.follow.upsert({
        where: { followerId_followeeId: { followerId: req.user!.id, followeeId: target.id } },
        create: { followerId: req.user!.id, followeeId: target.id },
        update: {},
      });
    }
    return profileJson(target, true);
  });

  app.delete('/api/profiles/:username/follow', { preHandler: requireAuth }, async (req) => {
    const { target } = await profileOf((req.params as any).username);
    await prisma.follow.deleteMany({ where: { followerId: req.user!.id, followeeId: target.id } });
    return profileJson(target, false);
  });

  // ---- Articles ----
  async function listArticles(req: FastifyRequest, where: Prisma.ArticleWhereInput) {
    const q = req.query as Record<string, string>;
    const limit = intParam(q.limit, 20, 100);
    const offset = intParam(q.offset, 0);
    const viewerId = req.user?.id;
    const [rows, count] = await Promise.all([
      prisma.article.findMany({
        where,
        orderBy: { id: 'desc' },
        take: limit,
        skip: offset,
        include: articleInclude(viewerId),
      }),
      prisma.article.count({ where }),
    ]);
    return { articles: rows.map((a) => articleJson(a, false)), articlesCount: count };
  }

  app.get('/api/articles/feed', { preHandler: requireAuth }, async (req) =>
    listArticles(req, { author: { followers: { some: { followerId: req.user!.id } } } }),
  );

  app.get('/api/articles', { preHandler: optionalAuth }, async (req) => {
    const q = req.query as Record<string, string>;
    const where: Prisma.ArticleWhereInput = {};
    if (q.tag) where.tags = { some: { tag: { name: q.tag } } };
    if (q.author) where.author = { username: q.author };
    if (q.favorited) where.favorites = { some: { user: { username: q.favorited } } };
    return listArticles(req, where);
  });

  app.post('/api/articles', { preHandler: requireAuth }, async (req, reply) => {
    const a = isObj(req.body) && isObj(req.body.article) ? req.body.article : {};
    const errors: Errors = {};
    for (const f of ['title', 'description', 'body']) if (blank(a[f])) errors[f] = ["can't be blank"];
    if ('tagList' in a && a.tagList !== undefined) {
      try { parseTags(a.tagList); } catch (e) { if (e instanceof HttpError) Object.assign(errors, e.errors); }
    }
    if (Object.keys(errors).length) throw new HttpError(422, errors);
    const tags = a.tagList ? parseTags(a.tagList) : [];
    const created = await prisma.article.create({
      data: {
        slug: slugify(a.title),
        title: a.title,
        description: a.description,
        body: a.body,
        authorId: req.user!.id,
        tags: {
          create: tags.map((name) => ({
            tag: { connectOrCreate: { where: { name }, create: { name } } },
          })),
        },
      },
    });
    const full = await findArticle(created.slug, req.user!.id);
    return reply.code(201).send({ article: articleJson(full, true) });
  });

  app.get('/api/articles/:slug', { preHandler: optionalAuth }, async (req) => {
    const a = await findArticle((req.params as any).slug, req.user?.id);
    return { article: articleJson(a, true) };
  });

  app.put('/api/articles/:slug', { preHandler: requireAuth }, async (req) => {
    const slug = (req.params as any).slug;
    const existing = await findArticle(slug);
    if (existing.authorId !== req.user!.id) fail(403, 'article', 'forbidden');
    const a = isObj(req.body) && isObj(req.body.article) ? req.body.article : {};
    const errors: Errors = {};
    const data: Prisma.ArticleUpdateInput = {};
    for (const f of ['title', 'description', 'body'] as const) {
      if (f in a) {
        if (blank(a[f])) errors[f] = ["can't be blank"];
        else data[f] = a[f];
      }
    }
    let tags: string[] | undefined;
    if ('tagList' in a) {
      try { tags = parseTags(a.tagList); } catch (e) { if (e instanceof HttpError) Object.assign(errors, e.errors); }
    }
    if (Object.keys(errors).length) throw new HttpError(422, errors);
    data.updatedAt = new Date();
    if (tags) {
      data.tags = {
        deleteMany: {},
        create: tags.map((name) => ({ tag: { connectOrCreate: { where: { name }, create: { name } } } })),
      };
    }
    await prisma.article.update({ where: { id: existing.id }, data });
    const full = await findArticle(slug, req.user!.id);
    return { article: articleJson(full, true) };
  });

  app.delete('/api/articles/:slug', { preHandler: requireAuth }, async (req, reply) => {
    const existing = await findArticle((req.params as any).slug);
    if (existing.authorId !== req.user!.id) fail(403, 'article', 'forbidden');
    await prisma.article.delete({ where: { id: existing.id } });
    return reply.code(204).send();
  });

  // ---- Favorites ----
  app.post('/api/articles/:slug/favorite', { preHandler: requireAuth }, async (req) => {
    const slug = (req.params as any).slug;
    const a = await findArticle(slug);
    await prisma.favorite.upsert({
      where: { userId_articleId: { userId: req.user!.id, articleId: a.id } },
      create: { userId: req.user!.id, articleId: a.id },
      update: {},
    });
    return { article: articleJson(await findArticle(slug, req.user!.id), true) };
  });

  app.delete('/api/articles/:slug/favorite', { preHandler: requireAuth }, async (req) => {
    const slug = (req.params as any).slug;
    const a = await findArticle(slug);
    await prisma.favorite.deleteMany({ where: { userId: req.user!.id, articleId: a.id } });
    return { article: articleJson(await findArticle(slug, req.user!.id), true) };
  });

  // ---- Comments ----
  const commentInclude = (viewerId?: number) => ({
    author: {
      include: viewerId
        ? { followers: { where: { followerId: viewerId }, select: { followerId: true } } }
        : undefined,
    },
  }) as any;

  app.post('/api/articles/:slug/comments', { preHandler: requireAuth }, async (req, reply) => {
    const a = await findArticle((req.params as any).slug);
    const c = isObj(req.body) && isObj(req.body.comment) ? req.body.comment : {};
    if (blank(c.body)) fail(422, 'body', "can't be blank");
    const created = await prisma.comment.create({
      data: { body: c.body, articleId: a.id, authorId: req.user!.id },
      include: commentInclude(req.user!.id),
    });
    return reply.code(201).send({ comment: commentJson(created) });
  });

  app.get('/api/articles/:slug/comments', { preHandler: optionalAuth }, async (req) => {
    const a = await findArticle((req.params as any).slug);
    const rows = await prisma.comment.findMany({
      where: { articleId: a.id },
      orderBy: { id: 'desc' },
      include: commentInclude(req.user?.id),
    });
    return { comments: rows.map(commentJson) };
  });

  app.put('/api/articles/:slug/comments/:id', { preHandler: requireAuth }, async (req) => {
    const a = await findArticle((req.params as any).slug);
    const id = Number((req.params as any).id);
    const c = Number.isInteger(id) ? await prisma.comment.findUnique({ where: { id } }) : null;
    if (!c || c.articleId !== a.id) fail(404, 'comment', 'not found');
    if (c!.authorId !== req.user!.id) fail(403, 'comment', 'forbidden');
    const input = isObj(req.body) && isObj(req.body.comment) ? req.body.comment : {};
    if (blank(input.body)) fail(422, 'body', "can't be blank");
    const updated = await prisma.comment.update({
      where: { id },
      data: { body: input.body },
      include: commentInclude(req.user!.id),
    });
    return { comment: commentJson(updated) };
  });

  app.delete('/api/articles/:slug/comments/:id', { preHandler: requireAuth }, async (req, reply) => {
    const a = await findArticle((req.params as any).slug);
    const id = parseInt((req.params as any).id, 10);
    const c = Number.isFinite(id) ? await prisma.comment.findUnique({ where: { id } }) : null;
    if (!c || c.articleId !== a.id) fail(404, 'comment', 'not found');
    if (c!.authorId !== req.user!.id) fail(403, 'comment', 'forbidden');
    await prisma.comment.delete({ where: { id } });
    return reply.code(204).send();
  });

  // ---- Tags ----
  app.get('/api/tags', async () => {
    const tags = await prisma.tag.findMany({ where: { articles: { some: {} } }, orderBy: { name: 'asc' } });
    return { tags: tags.map((t) => t.name) };
  });

  return app;
}
