# ---- build stage: install all deps, generate Prisma client, compile TypeScript ----
FROM node:24-slim AS build
WORKDIR /app
RUN apt-get update -y && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci --include=dev --no-audit --no-fund
COPY tsconfig.json ./
COPY prisma ./prisma
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ---- runtime stage: production dependencies and compiled output only ----
FROM node:24-slim
WORKDIR /app
RUN apt-get update -y && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
ENV NODE_ENV=production \
    PORT=3000 \
    DATABASE_URL=file:/data/app.db
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/prisma ./prisma
RUN mkdir -p /data
VOLUME /data
EXPOSE 3000
# The primary process creates the schema on an empty database, then forks one worker per CPU core.
CMD ["node", "dist/server.js"]
