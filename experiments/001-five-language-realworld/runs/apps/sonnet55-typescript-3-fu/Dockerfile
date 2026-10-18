# syntax=docker/dockerfile:1
FROM node:24-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY prisma ./prisma
COPY src ./src
RUN npm run build
# Keep only production dependencies; the generated Prisma client lives in node_modules/.prisma.
RUN npm prune --omit=dev

FROM node:24-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
ENV NODE_ENV=production \
    PORT=3000 \
    DATABASE_URL=file:/data/app.db
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/package.json ./package.json
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 3000
# The server creates its schema on startup when the database is empty.
CMD ["node", "dist/index.js"]
