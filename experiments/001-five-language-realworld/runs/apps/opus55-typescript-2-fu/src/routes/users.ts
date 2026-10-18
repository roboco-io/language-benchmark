import type { FastifyInstance } from 'fastify';
import type { PrismaClient, User } from '@prisma/client';
import { hashPassword, requireUserId, signToken, verifyPassword } from '../auth';
import { conflict, HttpError, unauthorized, Validator } from '../errors';
import { isUniqueViolation } from '../prismaErrors';
import { BLANK, envelope, isNonBlankString, isPresent, passwordProblem } from '../validation';

function toUser(user: User) {
  return {
    email: user.email,
    token: signToken(user.id),
    username: user.username,
    bio: user.bio,
    image: user.image,
  };
}

/** Converts a unique-constraint failure on email or username into a 409 response. */
function rethrowDuplicate(error: unknown): never {
  if (isUniqueViolation(error, 'email')) throw conflict('email');
  if (isUniqueViolation(error, 'username')) throw conflict('username');
  throw error;
}

async function assertAvailable(prisma: PrismaClient, fields: { email?: string; username?: string }, selfId?: number) {
  if (fields.username !== undefined) {
    const existing = await prisma.user.findUnique({ where: { username: fields.username }, select: { id: true } });
    if (existing && existing.id !== selfId) throw conflict('username');
  }
  if (fields.email !== undefined) {
    const existing = await prisma.user.findUnique({ where: { email: fields.email }, select: { id: true } });
    if (existing && existing.id !== selfId) throw conflict('email');
  }
}

/** Accepts null or a string for optional profile fields; empty strings are stored as null. */
function nullableText(value: unknown, field: string, validator: Validator): string | null | undefined {
  if (value === null) return null;
  if (typeof value !== 'string') {
    validator.add(field, 'must be a string or null');
    return undefined;
  }
  return value.trim() === '' ? null : value;
}

export async function loadCurrentUser(prisma: PrismaClient, userId: number): Promise<User> {
  const user = await prisma.user.findUnique({ where: { id: userId } });
  if (!user) throw unauthorized('is invalid');
  return user;
}

export default async function userRoutes(app: FastifyInstance, { prisma }: { prisma: PrismaClient }) {
  app.post('/api/users', async (request, reply) => {
    const input = envelope(request.body, 'user');
    const validator = new Validator();
    if (!isNonBlankString(input.username)) validator.add('username', BLANK);
    if (!isNonBlankString(input.email)) validator.add('email', BLANK);
    const passwordError = passwordProblem(input.password);
    if (passwordError) validator.add('password', passwordError);
    validator.throwIfAny();

    const username = (input.username as string).trim();
    const email = (input.email as string).trim();
    await assertAvailable(prisma, { username, email });
    const password = await hashPassword(input.password as string);
    const user = await prisma.user.create({ data: { username, email, password } }).catch(rethrowDuplicate);
    return reply.code(201).send({ user: toUser(user) });
  });

  app.post('/api/users/login', async (request) => {
    const input = envelope(request.body, 'user');
    const validator = new Validator();
    if (!isNonBlankString(input.email)) validator.add('email', BLANK);
    if (typeof input.password !== 'string' || input.password.length === 0) validator.add('password', BLANK);
    validator.throwIfAny();

    const user = await prisma.user.findUnique({ where: { email: (input.email as string).trim() } });
    if (!user || !(await verifyPassword(input.password as string, user.password))) {
      throw new HttpError(401, { credentials: ['invalid'] });
    }
    return { user: toUser(user) };
  });

  app.get('/api/user', async (request) => {
    const user = await loadCurrentUser(prisma, requireUserId(request));
    return { user: toUser(user) };
  });

  app.put('/api/user', async (request) => {
    const userId = requireUserId(request);
    const input = envelope(request.body, 'user');
    const validator = new Validator();
    const data: { email?: string; username?: string; password?: string; bio?: string | null; image?: string | null } = {};

    for (const field of ['email', 'username'] as const) {
      if (!isPresent(input, field)) continue;
      const value = input[field];
      if (isNonBlankString(value)) data[field] = value.trim();
      else validator.add(field, BLANK);
    }
    if (isPresent(input, 'password')) {
      const problem = passwordProblem(input.password);
      if (problem) validator.add('password', problem);
    }
    for (const field of ['bio', 'image'] as const) {
      if (!isPresent(input, field)) continue;
      const value = nullableText(input[field], field, validator);
      if (value !== undefined) data[field] = value;
    }
    validator.throwIfAny();

    await loadCurrentUser(prisma, userId);
    await assertAvailable(prisma, data, userId);
    if (isPresent(input, 'password')) data.password = await hashPassword(input.password as string);
    const user = await prisma.user.update({ where: { id: userId }, data }).catch(rethrowDuplicate);
    return { user: toUser(user) };
  });
}
