import type { FastifyInstance } from "fastify";
import { hashPassword, requireUserId, signToken, verifyPassword } from "../auth.js";
import { prisma } from "../db.js";
import { conflict, unauthorized } from "../errors.js";
import type { User } from "../generated/prisma/client.js";
import { isUniqueViolation } from "../prisma-errors.js";
import {
  checkEmail,
  checkPassword,
  optionalNonBlankString,
  optionalNullableString,
  requiredString,
  wrapper,
} from "../validation.js";

function userResponse(user: User) {
  return {
    user: {
      email: user.email,
      token: signToken(user.id),
      username: user.username,
      bio: user.bio,
      image: user.image,
    },
  };
}

// Reports which unique field (username first, then email) is already taken.
async function assertAvailable(username: string | undefined, email: string | undefined, selfId?: number) {
  if (username !== undefined) {
    const existing = await prisma.user.findUnique({ where: { username }, select: { id: true } });
    if (existing && existing.id !== selfId) throw conflict("username");
  }
  if (email !== undefined) {
    const existing = await prisma.user.findUnique({ where: { email }, select: { id: true } });
    if (existing && existing.id !== selfId) throw conflict("email");
  }
}

function uniqueConflict(error: unknown): never {
  if (isUniqueViolation(error)) {
    throw conflict(String(error.meta?.target ?? "").includes("email") ? "email" : "username");
  }
  throw error;
}

export async function userRoutes(app: FastifyInstance) {
  app.post("/api/users", async (request, reply) => {
    const input = wrapper(request.body, "user");
    const username = requiredString(input, "username");
    const email = requiredString(input, "email");
    const password = requiredString(input, "password");
    checkEmail(email);
    checkPassword(password);
    await assertAvailable(username, email);
    const passwordHash = await hashPassword(password);
    const user = await prisma.user
      .create({ data: { username, email, passwordHash } })
      .catch(uniqueConflict);
    reply.code(201);
    return userResponse(user);
  });

  app.post("/api/users/login", async (request) => {
    const input = wrapper(request.body, "user");
    const email = requiredString(input, "email");
    const password = requiredString(input, "password");
    const user = await prisma.user.findUnique({ where: { email } });
    if (!user || !(await verifyPassword(password, user.passwordHash))) {
      throw unauthorized("credentials", "invalid");
    }
    return userResponse(user);
  });

  app.get("/api/user", async (request) => {
    const userId = requireUserId(request);
    const user = await prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw unauthorized("token", "is invalid");
    return userResponse(user);
  });

  app.put("/api/user", async (request) => {
    const userId = requireUserId(request);
    const input = wrapper(request.body, "user");
    const username = optionalNonBlankString(input, "username");
    const email = optionalNonBlankString(input, "email");
    const password = optionalNonBlankString(input, "password");
    const bio = optionalNullableString(input, "bio");
    const image = optionalNullableString(input, "image");
    if (email !== undefined) checkEmail(email);
    if (password !== undefined) checkPassword(password);

    const current = await prisma.user.findUnique({ where: { id: userId }, select: { id: true } });
    if (!current) throw unauthorized("token", "is invalid");
    await assertAvailable(username, email, userId);

    const user = await prisma.user
      .update({
        where: { id: userId },
        data: {
          username,
          email,
          bio,
          image,
          passwordHash: password === undefined ? undefined : await hashPassword(password),
        },
      })
      .catch(uniqueConflict);
    return userResponse(user);
  });
}
