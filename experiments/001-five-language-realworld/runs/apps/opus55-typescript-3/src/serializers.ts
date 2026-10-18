import type { Prisma } from '@prisma/client';

// Viewer id -1 never matches a real row, so anonymous requests reuse the same query shape.
const viewerOrNone = (viewerId: number | null) => viewerId ?? -1;

export function profileInclude(viewerId: number | null) {
  return {
    followers: { where: { followerId: viewerOrNone(viewerId) }, select: { followerId: true } },
  } satisfies Prisma.UserInclude;
}

type UserWithFollowers = Prisma.UserGetPayload<{ include: ReturnType<typeof profileInclude> }>;

export function toProfile(user: UserWithFollowers) {
  return {
    username: user.username,
    bio: user.bio,
    image: user.image,
    following: user.followers.length > 0,
  };
}

export function articleInclude(viewerId: number | null) {
  return {
    author: { include: profileInclude(viewerId) },
    tags: { orderBy: { position: 'asc' }, include: { tag: true } },
    favorites: { where: { userId: viewerOrNone(viewerId) }, select: { userId: true } },
    _count: { select: { favorites: true } },
  } satisfies Prisma.ArticleInclude;
}

type ArticleWithRelations = Prisma.ArticleGetPayload<{ include: ReturnType<typeof articleInclude> }>;

export function toArticle(article: ArticleWithRelations, includeBody = true) {
  return {
    slug: article.slug,
    title: article.title,
    description: article.description,
    ...(includeBody ? { body: article.body } : {}),
    tagList: article.tags.map((t) => t.tag.name),
    createdAt: article.createdAt.toISOString(),
    updatedAt: article.updatedAt.toISOString(),
    favorited: article.favorites.length > 0,
    favoritesCount: article._count.favorites,
    author: toProfile(article.author),
  };
}

export function commentInclude(viewerId: number | null) {
  return { author: { include: profileInclude(viewerId) } } satisfies Prisma.CommentInclude;
}

type CommentWithAuthor = Prisma.CommentGetPayload<{ include: ReturnType<typeof commentInclude> }>;

export function toComment(comment: CommentWithAuthor) {
  return {
    id: comment.id,
    createdAt: comment.createdAt.toISOString(),
    updatedAt: comment.updatedAt.toISOString(),
    body: comment.body,
    author: toProfile(comment.author),
  };
}
