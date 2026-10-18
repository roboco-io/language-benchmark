import type { FastifyInstance } from 'fastify';
import { prisma } from '../db';
import { readUserId, requireUserId } from '../auth';
import { notFound } from '../errors';
import { profileInclude, toProfile } from '../serializers';

type Params = { username: string };

async function findProfile(username: string, viewerId: number | null) {
  const user = await prisma.user.findUnique({ where: { username }, include: profileInclude(viewerId) });
  if (!user) throw notFound('profile');
  return user;
}

export async function profileRoutes(app: FastifyInstance) {
  app.get<{ Params: Params }>('/api/profiles/:username', async (request) => {
    const user = await findProfile(request.params.username, readUserId(request));
    return { profile: toProfile(user) };
  });

  app.post<{ Params: Params }>('/api/profiles/:username/follow', async (request) => {
    const viewerId = requireUserId(request);
    const target = await findProfile(request.params.username, viewerId);
    if (target.id !== viewerId) {
      await prisma.follow.upsert({
        where: { followerId_followingId: { followerId: viewerId, followingId: target.id } },
        create: { followerId: viewerId, followingId: target.id },
        update: {},
      });
    }
    return { profile: toProfile(await findProfile(target.username, viewerId)) };
  });

  app.delete<{ Params: Params }>('/api/profiles/:username/follow', async (request) => {
    const viewerId = requireUserId(request);
    const target = await findProfile(request.params.username, viewerId);
    await prisma.follow.deleteMany({ where: { followerId: viewerId, followingId: target.id } });
    return { profile: toProfile(await findProfile(target.username, viewerId)) };
  });
}
