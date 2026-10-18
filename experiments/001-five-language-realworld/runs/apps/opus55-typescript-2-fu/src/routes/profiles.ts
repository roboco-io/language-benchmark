import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';
import { optionalUserId, requireUserId } from '../auth';
import { notFound } from '../errors';
import { profileSelect, toProfile } from '../serializers';
import { loadCurrentUser } from './users';

type Params = { username: string };

export default async function profileRoutes(app: FastifyInstance, { prisma }: { prisma: PrismaClient }) {
  async function findProfile(username: string, viewerId?: number) {
    const user = await prisma.user.findUnique({ where: { username }, select: { id: true, ...profileSelect(viewerId) } });
    if (!user) throw notFound('profile');
    return user;
  }

  app.get<{ Params: Params }>('/api/profiles/:username', async (request) => {
    const viewerId = optionalUserId(request);
    return { profile: toProfile(await findProfile(request.params.username, viewerId)) };
  });

  app.post<{ Params: Params }>('/api/profiles/:username/follow', async (request) => {
    const viewerId = requireUserId(request);
    await loadCurrentUser(prisma, viewerId);
    const target = await findProfile(request.params.username, viewerId);
    if (target.id !== viewerId) {
      await prisma.follow.upsert({
        where: { followerId_followingId: { followerId: viewerId, followingId: target.id } },
        create: { followerId: viewerId, followingId: target.id },
        update: {},
      });
    }
    return { profile: toProfile(await findProfile(request.params.username, viewerId)) };
  });

  app.delete<{ Params: Params }>('/api/profiles/:username/follow', async (request) => {
    const viewerId = requireUserId(request);
    await loadCurrentUser(prisma, viewerId);
    const target = await findProfile(request.params.username, viewerId);
    await prisma.follow.deleteMany({ where: { followerId: viewerId, followingId: target.id } });
    return { profile: toProfile(await findProfile(request.params.username, viewerId)) };
  });
}
