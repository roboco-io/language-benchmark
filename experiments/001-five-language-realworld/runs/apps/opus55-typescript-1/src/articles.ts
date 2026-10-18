import crypto from "node:crypto";
import { prisma } from "./db.js";
import { notFound } from "./errors.js";
import type { Prisma } from "./generated/prisma/client.js";
import { profileView } from "./routes/profiles.js";

// Relations needed to render an article for a given viewer. A viewer id of -1
// (anonymous) matches no rows, so "favorited" and "following" become false.
export function articleInclude(viewerId: number | null) {
  const viewer = viewerId ?? -1;
  return {
    author: {
      select: {
        username: true,
        bio: true,
        image: true,
        followers: { where: { followerId: viewer }, select: { followerId: true } },
      },
    },
    tags: { orderBy: { position: "asc" }, select: { tag: { select: { name: true } } } },
    favorites: { where: { userId: viewer }, select: { userId: true } },
    _count: { select: { favorites: true } },
  } satisfies Prisma.ArticleInclude;
}

type ArticleRow = Prisma.ArticleGetPayload<{ include: ReturnType<typeof articleInclude> }>;
type ArticleListRow = Omit<ArticleRow, "body">;

export function articleView(article: ArticleListRow & { body?: string }) {
  return {
    slug: article.slug,
    title: article.title,
    description: article.description,
    ...(article.body === undefined ? {} : { body: article.body }),
    tagList: article.tags.map((entry) => entry.tag.name),
    createdAt: article.createdAt.toISOString(),
    updatedAt: article.updatedAt.toISOString(),
    favorited: article.favorites.length > 0,
    favoritesCount: article._count.favorites,
    author: profileView(article.author, article.author.followers.length > 0),
  };
}

export async function loadArticle(slug: string, viewerId: number | null) {
  const article = await prisma.article.findUnique({ where: { slug }, include: articleInclude(viewerId) });
  if (!article) throw notFound("article");
  return article;
}

export async function findArticleRef(slug: string) {
  const article = await prisma.article.findUnique({
    where: { slug },
    select: { id: true, authorId: true, updatedAt: true },
  });
  if (!article) throw notFound("article");
  return article;
}

export function slugify(title: string): string {
  const base = title
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80)
    .replace(/-+$/, "");
  return base === "" ? "article" : base;
}

// Picks a slug derived from the title that no other article uses. Titles are
// not unique, so a short random suffix is appended when the plain slug is taken.
export async function uniqueSlug(title: string, tx: Prisma.TransactionClient = prisma): Promise<string> {
  const base = slugify(title);
  let candidate = base;
  for (;;) {
    const existing = await tx.article.findUnique({ where: { slug: candidate }, select: { id: true } });
    if (!existing) return candidate;
    candidate = `${base}-${crypto.randomBytes(4).toString("hex")}`;
  }
}

// Replaces the tags of an article, keeping the order in which they were given.
export async function setArticleTags(tx: Prisma.TransactionClient, articleId: number, tags: string[]) {
  await tx.articleTag.deleteMany({ where: { articleId } });
  if (tags.length === 0) return;
  for (const name of tags) {
    await tx.$executeRaw`INSERT OR IGNORE INTO "Tag" ("name") VALUES (${name})`;
  }
  const rows = await tx.tag.findMany({ where: { name: { in: tags } }, select: { id: true, name: true } });
  const idByName = new Map(rows.map((row) => [row.name, row.id]));
  await tx.articleTag.createMany({
    data: tags.map((name, position) => ({ articleId, tagId: idByName.get(name)!, position })),
  });
}
