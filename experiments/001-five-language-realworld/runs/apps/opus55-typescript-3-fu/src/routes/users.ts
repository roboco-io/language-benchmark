import type { FastifyInstance } from 'fastify';
import { Prisma, type User } from '@prisma/client';
import { prisma } from '../db';
import { hashPassword, requireUserId, signToken, verifyPassword } from '../auth';
import { conflict, HttpError, unauthorized, Validator } from '../errors';
import * as check from '../validation';

function toUser(user: User) {
  return {
    email: user.email,
    token: signToken(user),
    username: user.username,
    bio: user.bio,
    image: user.image,
  };
}

// Converts a unique constraint violation on email or username into a 409 response.
function rethrowUniqueViolation(error: unknown): never {
  if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
    const target = String((error.meta?.target as string[] | string | undefined) ?? '');
    throw conflict(target.includes('email') ? 'email' : 'username');
  }
  throw error;
}

async function ensureAvailable(field: 'email' | 'username', value: string, exceptId?: number) {
  const where = field === 'email' ? { email: value } : { username: value };
  const existing = await prisma.user.findUnique({ where });
  if (existing && existing.id !== exceptId) throw conflict(field);
}

export async function userRoutes(app: FastifyInstance) {
  app.post('/api/users', async (request, reply) => {
    const input = check.wrapper(request.body, 'user');
    const v = new Validator();
    const username = check.requiredString(v, input, 'username');
    const email = check.requiredString(v, input, 'email');
    const password = check.password(v, input);
    v.throwIfInvalid();

    await ensureAvailable('username', username!);
    await ensureAvailable('email', email!);
    const user = await prisma.user
      .create({ data: { username: username!, email: email!, password: await hashPassword(password!) } })
      .catch(rethrowUniqueViolation);
    return reply.code(201).send({ user: toUser(user) });
  });

  app.post('/api/users/login', async (request) => {
    const input = check.wrapper(request.body, 'user');
    const v = new Validator();
    const email = check.requiredString(v, input, 'email');
    const password = input.password;
    if (typeof password !== 'string' || password === '') v.add('password', "can't be blank");
    v.throwIfInvalid();

    const user = await prisma.user.findUnique({ where: { email: email! } });
    if (!user || !(await verifyPassword(password as string, user.password))) {
      throw new HttpError(401, { credentials: ['invalid'] });
    }
    return { user: toUser(user) };
  });

  app.get('/api/user', async (request) => {
    const user = await prisma.user.findUnique({ where: { id: requireUserId(request) } });
    if (!user) throw unauthorized('is invalid');
    return { user: toUser(user) };
  });

  app.put('/api/user', async (request) => {
    const userId = requireUserId(request);
    const input = check.wrapper(request.body, 'user');
    const v = new Validator();
    const data: Prisma.UserUpdateInput = {};
    const username = check.optionalNonEmptyString(v, input, 'username');
    const email = check.optionalNonEmptyString(v, input, 'email');
    const bio = check.optionalNullableString(v, input, 'bio');
    const image = check.optionalNullableString(v, input, 'image');
    const password = 'password' in input ? check.password(v, input) : undefined;
    v.throwIfInvalid();

    if (!(await prisma.user.findUnique({ where: { id: userId } }))) throw unauthorized('is invalid');
    if (username !== undefined) {
      await ensureAvailable('username', username, userId);
      data.username = username;
    }
    if (email !== undefined) {
      await ensureAvailable('email', email, userId);
      data.email = email;
    }
    if (bio !== undefined) data.bio = bio;
    if (image !== undefined) data.image = image;
    if (password !== undefined) data.password = await hashPassword(password);

    const user = await prisma.user.update({ where: { id: userId }, data }).catch(rethrowUniqueViolation);
    return { user: toUser(user) };
  });
}
