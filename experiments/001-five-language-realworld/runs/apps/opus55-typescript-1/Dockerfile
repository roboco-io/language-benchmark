# syntax=docker/dockerfile:1

# ---- Build stage: install all dependencies, generate the Prisma client, compile TypeScript.
FROM node:24-bookworm-slim AS build
WORKDIR /app
# Toolchain for compiling better-sqlite3 if no prebuilt binary matches the platform.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 make g++ \
 && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json prisma.config.ts ./
COPY prisma ./prisma
COPY src ./src
RUN npm run build
# Reinstall without dev and peer dependencies: @prisma/client declares the
# Prisma CLI and TypeScript as peers, which are not needed at runtime.
RUN npm pkg delete devDependencies \
 && rm -rf node_modules \
 && npm install --omit=dev --omit=peer --no-audit --no-fund

# ---- Runtime stage: only production dependencies, compiled output and migrations.
FROM node:24-bookworm-slim AS runtime
ENV NODE_ENV=production \
    PORT=3000 \
    DATABASE_URL=file:/app/data/realworld.db
WORKDIR /app
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/prisma/migrations ./prisma/migrations
RUN mkdir -p /app/data && chown node:node /app/data
USER node
VOLUME ["/app/data"]
EXPOSE 3000
CMD ["node", "dist/index.js"]
