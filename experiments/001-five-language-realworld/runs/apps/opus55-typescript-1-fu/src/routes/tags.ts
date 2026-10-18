import type { FastifyInstance } from "fastify";
import { prisma } from "../db.js";

export async function tagRoutes(app: FastifyInstance) {
  app.get("/api/tags", async () => {
    const tags = await prisma.tag.findMany({
      where: { articles: { some: {} } },
      select: { name: true },
      orderBy: { name: "asc" },
    });
    return { tags: tags.map((tag) => tag.name) };
  });
}
