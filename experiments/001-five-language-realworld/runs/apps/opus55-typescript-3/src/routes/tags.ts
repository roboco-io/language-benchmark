import type { FastifyInstance } from 'fastify';
import { prisma } from '../db';

export async function tagRoutes(app: FastifyInstance) {
  // Only tags that are attached to at least one article are listed.
  app.get('/api/tags', async () => {
    const tags = await prisma.tag.findMany({
      where: { articles: { some: {} } },
      orderBy: { name: 'asc' },
      select: { name: true },
    });
    return { tags: tags.map((t) => t.name) };
  });
}
