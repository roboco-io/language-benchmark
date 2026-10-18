import type { FastifyInstance } from "fastify";
import { findArticleRef } from "../articles.js";
import { optionalUserId, requireUserId } from "../auth.js";
import { prisma } from "../db.js";
import { forbidden, notFound } from "../errors.js";
import type { Prisma } from "../generated/prisma/client.js";
import { requiredString, wrapper } from "../validation.js";
import { profileView } from "./profiles.js";

function commentInclude(viewerId: number | null) {
  return {
    author: {
      select: {
        username: true,
        bio: true,
        image: true,
        followers: { where: { followerId: viewerId ?? -1 }, select: { followerId: true } },
      },
    },
  } satisfies Prisma.CommentInclude;
}

type CommentRow = Prisma.CommentGetPayload<{ include: ReturnType<typeof commentInclude> }>;

function commentView(comment: CommentRow) {
  return {
    id: comment.id,
    createdAt: comment.createdAt.toISOString(),
    updatedAt: comment.updatedAt.toISOString(),
    body: comment.body,
    author: profileView(comment.author, comment.author.followers.length > 0),
  };
}

// Resolves a comment of the given article and checks that the user wrote it.
// Returns the comment id, or throws 404 (article/comment) or 403.
async function findOwnComment(params: { slug: string; id: string }, userId: number): Promise<number> {
  const article = await findArticleRef(params.slug);
  const id = /^\d+$/.test(params.id) ? Number(params.id) : Number.NaN;
  const comment = Number.isSafeInteger(id)
    ? await prisma.comment.findUnique({ where: { id }, select: { articleId: true, authorId: true } })
    : null;
  if (!comment || comment.articleId !== article.id) throw notFound("comment");
  if (comment.authorId !== userId) throw forbidden("comment");
  return id;
}

type SlugParams = { Params: { slug: string } };
type CommentParams = { Params: { slug: string; id: string } };

export async function commentRoutes(app: FastifyInstance) {
  app.get<SlugParams>("/api/articles/:slug/comments", async (request) => {
    const viewerId = optionalUserId(request);
    const article = await findArticleRef(request.params.slug);
    const comments = await prisma.comment.findMany({
      where: { articleId: article.id },
      include: commentInclude(viewerId),
      orderBy: [{ createdAt: "asc" }, { id: "asc" }],
    });
    return { comments: comments.map(commentView) };
  });

  app.post<SlugParams>("/api/articles/:slug/comments", async (request, reply) => {
    const userId = requireUserId(request);
    const article = await findArticleRef(request.params.slug);
    const body = requiredString(wrapper(request.body, "comment"), "body");
    const now = new Date();
    const comment = await prisma.comment.create({
      data: { body, articleId: article.id, authorId: userId, createdAt: now, updatedAt: now },
      include: commentInclude(userId),
    });
    reply.code(201);
    return { comment: commentView(comment) };
  });

  app.put<CommentParams>("/api/articles/:slug/comments/:id", async (request) => {
    const userId = requireUserId(request);
    const id = await findOwnComment(request.params, userId);
    const body = requiredString(wrapper(request.body, "comment"), "body");
    const comment = await prisma.comment.update({
      where: { id },
      data: { body, updatedAt: new Date() },
      include: commentInclude(userId),
    });
    return { comment: commentView(comment) };
  });

  app.delete<CommentParams>("/api/articles/:slug/comments/:id", async (request, reply) => {
    const userId = requireUserId(request);
    const id = await findOwnComment(request.params, userId);
    await prisma.comment.delete({ where: { id } });
    reply.code(204).send();
  });
}
