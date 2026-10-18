import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';

export default async function tagRoutes(app: FastifyInstance, { prisma }: { prisma: PrismaClient }) {
  app.get('/api/tags', async () => {
    const tags = await prisma.tag.findMany({
      where: { articles: { some: {} } },
      orderBy: [{ articles: { _count: 'desc' } }, { name: 'asc' }],
      select: { name: true },
    });
    return { tags: tags.map((t) => t.name) };
  });
}
