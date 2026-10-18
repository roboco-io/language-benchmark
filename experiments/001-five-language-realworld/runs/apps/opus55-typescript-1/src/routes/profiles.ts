import type { FastifyInstance } from "fastify";
import { optionalUserId, requireUserId } from "../auth.js";
import { prisma } from "../db.js";
import { notFound } from "../errors.js";

type ProfileSource = { username: string; bio: string | null; image: string | null };

export function profileView(user: ProfileSource, following: boolean) {
  return { username: user.username, bio: user.bio, image: user.image, following };
}

async function findProfileUser(username: string) {
  const user = await prisma.user.findUnique({
    where: { username },
    select: { id: true, username: true, bio: true, image: true },
  });
  if (!user) throw notFound("profile");
  return user;
}

async function isFollowing(followerId: number | null, followeeId: number): Promise<boolean> {
  if (followerId === null) return false;
  const follow = await prisma.follow.findUnique({
    where: { followerId_followeeId: { followerId, followeeId } },
    select: { followerId: true },
  });
  return follow !== null;
}

type UsernameParams = { Params: { username: string } };

export async function profileRoutes(app: FastifyInstance) {
  app.get<UsernameParams>("/api/profiles/:username", async (request) => {
    const viewerId = optionalUserId(request);
    const user = await findProfileUser(request.params.username);
    return { profile: profileView(user, await isFollowing(viewerId, user.id)) };
  });

  app.post<UsernameParams>("/api/profiles/:username/follow", async (request) => {
    const viewerId = requireUserId(request);
    const user = await findProfileUser(request.params.username);
    await prisma.$executeRaw`INSERT OR IGNORE INTO "Follow" ("followerId", "followeeId") VALUES (${viewerId}, ${user.id})`;
    return { profile: profileView(user, true) };
  });

  app.delete<UsernameParams>("/api/profiles/:username/follow", async (request) => {
    const viewerId = requireUserId(request);
    const user = await findProfileUser(request.params.username);
    await prisma.follow.deleteMany({ where: { followerId: viewerId, followeeId: user.id } });
    return { profile: profileView(user, false) };
  });
}
