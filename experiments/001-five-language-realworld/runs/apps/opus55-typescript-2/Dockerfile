# syntax=docker/dockerfile:1

# ---- Build stage: install all dependencies, generate the Prisma client, compile TypeScript ----
FROM node:24-alpine AS build
RUN apk add --no-cache openssl
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY prisma ./prisma
COPY src ./src
RUN npm run build && npm prune --omit=dev --no-audit --no-fund

# ---- Runtime stage: production dependencies and compiled output only ----
FROM node:24-alpine
RUN apk add --no-cache openssl
WORKDIR /app
ENV NODE_ENV=production \
    PORT=8000 \
    DATABASE_URL=file:/app/data/realworld.db
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/prisma ./prisma
RUN mkdir -p /app/data && chown -R node:node /app/data
USER node
VOLUME ["/app/data"]
EXPOSE 8000
CMD ["node", "dist/index.js"]
