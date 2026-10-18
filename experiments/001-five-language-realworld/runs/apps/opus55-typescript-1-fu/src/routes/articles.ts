import type { FastifyInstance } from "fastify";
import {
  articleInclude,
  articleView,
  findArticleRef,
  loadArticle,
  setArticleTags,
  uniqueSlug,
} from "../articles.js";
import { optionalUserId, requireUserId } from "../auth.js";
import { prisma } from "../db.js";
import { forbidden } from "../errors.js";
import type { Prisma } from "../generated/prisma/client.js";
import { isUniqueViolation } from "../prisma-errors.js";
import {
  optionalNonBlankString,
  optionalTagList,
  queryInt,
  requiredString,
  wrapper,
} from "../validation.js";

type SlugParams = { Params: { slug: string } };
type ListQuery = {
  Querystring: { tag?: string; author?: string; favorited?: string; limit?: string; offset?: string };
};

const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 100;

async function listArticles(where: Prisma.ArticleWhereInput, query: ListQuery["Querystring"], viewerId: number | null) {
  const limit = queryInt(query.limit, DEFAULT_LIMIT, MAX_LIMIT);
  const offset = queryInt(query.offset, 0);
  const [articles, articlesCount] = await Promise.all([
    prisma.article.findMany({
      where,
      omit: { body: true },
      include: articleInclude(viewerId),
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      take: limit,
      skip: offset,
    }),
    prisma.article.count({ where }),
  ]);
  return { articles: articles.map(articleView), articlesCount };
}

// Timestamps must strictly increase on update, even within one millisecond.
function nextTimestamp(previous: Date): Date {
  const now = Date.now();
  return new Date(Math.max(now, previous.getTime() + 1));
}

// Retries an insert/update when another request claimed the same slug first.
async function withSlugRetry<T>(title: string, run: (slug: string) => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    const slug = await uniqueSlug(title);
    try {
      return await run(slug);
    } catch (error) {
      if (!isUniqueViolation(error) || attempt >= 5) throw error;
    }
  }
}

export async function articleRoutes(app: FastifyInstance) {
  app.get<ListQuery>("/api/articles", async (request) => {
    const viewerId = optionalUserId(request);
    const { tag, author, favorited } = request.query;
    const where: Prisma.ArticleWhereInput = {};
    if (tag) where.tags = { some: { tag: { name: tag } } };
    if (author) where.author = { username: author };
    if (favorited) where.favorites = { some: { user: { username: favorited } } };
    return listArticles(where, request.query, viewerId);
  });

  app.get<ListQuery>("/api/articles/feed", async (request) => {
    const viewerId = requireUserId(request);
    return listArticles({ author: { followers: { some: { followerId: viewerId } } } }, request.query, viewerId);
  });

  app.get<SlugParams>("/api/articles/:slug", async (request) => {
    const viewerId = optionalUserId(request);
    return { article: articleView(await loadArticle(request.params.slug, viewerId)) };
  });

  app.post("/api/articles", async (request, reply) => {
    const userId = requireUserId(request);
    const input = wrapper(request.body, "article");
    const title = requiredString(input, "title");
    const description = requiredString(input, "description");
    const body = requiredString(input, "body");
    const tags = optionalTagList(input) ?? [];
    const now = new Date();
    const slug = await withSlugRetry(title, (candidate) =>
      prisma.$transaction(async (tx) => {
        const article = await tx.article.create({
          data: { slug: candidate, title, description, body, authorId: userId, createdAt: now, updatedAt: now },
          select: { id: true, slug: true },
        });
        await setArticleTags(tx, article.id, tags);
        return article.slug;
      }),
    );
    reply.code(201);
    return { article: articleView(await loadArticle(slug, userId)) };
  });

  app.put<SlugParams>("/api/articles/:slug", async (request) => {
    const userId = requireUserId(request);
    const existing = await findArticleRef(request.params.slug);
    if (existing.authorId !== userId) throw forbidden("article");
    const input = wrapper(request.body, "article");
    const title = optionalNonBlankString(input, "title");
    const description = optionalNonBlankString(input, "description");
    const body = optionalNonBlankString(input, "body");
    const tags = optionalTagList(input);

    const current = await prisma.article.findUniqueOrThrow({ where: { id: existing.id }, select: { title: true, slug: true } });
    const titleChanged = title !== undefined && title !== current.title;
    const update = (slug: string) =>
      prisma.$transaction(async (tx) => {
        const article = await tx.article.update({
          where: { id: existing.id },
          data: { slug, title, description, body, updatedAt: nextTimestamp(existing.updatedAt) },
          select: { slug: true },
        });
        if (tags !== undefined) await setArticleTags(tx, existing.id, tags);
        return article.slug;
      });
    const slug = titleChanged ? await withSlugRetry(title, update) : await update(current.slug);
    return { article: articleView(await loadArticle(slug, userId)) };
  });

  app.delete<SlugParams>("/api/articles/:slug", async (request, reply) => {
    const userId = requireUserId(request);
    const existing = await findArticleRef(request.params.slug);
    if (existing.authorId !== userId) throw forbidden("article");
    await prisma.article.delete({ where: { id: existing.id } });
    reply.code(204).send();
  });

  app.post<SlugParams>("/api/articles/:slug/favorite", async (request) => {
    const userId = requireUserId(request);
    const { id } = await findArticleRef(request.params.slug);
    await prisma.$executeRaw`INSERT OR IGNORE INTO "Favorite" ("userId", "articleId") VALUES (${userId}, ${id})`;
    return { article: articleView(await loadArticle(request.params.slug, userId)) };
  });

  app.delete<SlugParams>("/api/articles/:slug/favorite", async (request) => {
    const userId = requireUserId(request);
    const { id } = await findArticleRef(request.params.slug);
    await prisma.favorite.deleteMany({ where: { userId, articleId: id } });
    return { article: articleView(await loadArticle(request.params.slug, userId)) };
  });
}
