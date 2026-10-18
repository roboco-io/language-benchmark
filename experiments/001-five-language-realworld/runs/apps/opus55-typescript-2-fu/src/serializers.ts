import { Prisma } from '@prisma/client';

/** Viewer id used when no user is logged in; never matches a real row. */
const ANONYMOUS = -1;

export function profileSelect(viewerId?: number) {
  return {
    username: true,
    bio: true,
    image: true,
    followers: { where: { followerId: viewerId ?? ANONYMOUS }, select: { followerId: true } },
  } satisfies Prisma.UserSelect;
}

export type ProfileRow = Prisma.UserGetPayload<{ select: ReturnType<typeof profileSelect> }>;

export function toProfile(user: ProfileRow) {
  return {
    username: user.username,
    bio: user.bio,
    image: user.image,
    following: user.followers.length > 0,
  };
}

export function articleInclude(viewerId?: number) {
  return {
    author: { select: profileSelect(viewerId) },
    tags: { orderBy: { position: 'asc' }, select: { tag: { select: { name: true } } } },
    favorites: { where: { userId: viewerId ?? ANONYMOUS }, select: { userId: true } },
    _count: { select: { favorites: true } },
  } satisfies Prisma.ArticleInclude;
}

export type ArticleRow = Prisma.ArticleGetPayload<{ include: ReturnType<typeof articleInclude> }>;

export function toArticle(article: ArticleRow, withBody = true) {
  return {
    slug: article.slug,
    title: article.title,
    description: article.description,
    ...(withBody ? { body: article.body } : {}),
    tagList: article.tags.map((t) => t.tag.name),
    createdAt: article.createdAt.toISOString(),
    updatedAt: article.updatedAt.toISOString(),
    favorited: article.favorites.length > 0,
    favoritesCount: article._count.favorites,
    author: toProfile(article.author),
  };
}

export function commentInclude(viewerId?: number) {
  return { author: { select: profileSelect(viewerId) } } satisfies Prisma.CommentInclude;
}

export type CommentRow = Prisma.CommentGetPayload<{ include: ReturnType<typeof commentInclude> }>;

export function toComment(comment: CommentRow) {
  return {
    id: comment.id,
    createdAt: comment.createdAt.toISOString(),
    updatedAt: comment.updatedAt.toISOString(),
    body: comment.body,
    author: toProfile(comment.author),
  };
}
