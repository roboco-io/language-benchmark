import jwt from 'jsonwebtoken';
import { FastifyRequest } from 'fastify';
import { unauthorized } from './http';

const SECRET = process.env.JWT_SECRET ?? 'realworld-dev-secret-change-me';
const TOKEN_TTL = '30d';

export function signToken(userId: number): string {
  return jwt.sign({ id: userId }, SECRET, { expiresIn: TOKEN_TTL });
}

/** Returns the user id from the Authorization header, null if absent. Throws on a bad token. */
export function userIdFromRequest(req: FastifyRequest): number | null {
  const header = req.headers.authorization;
  if (!header) return null;
  const match = /^(?:Token|Bearer)\s+(.+)$/i.exec(header);
  if (!match) throw unauthorized('is invalid');
  try {
    const payload = jwt.verify(match[1], SECRET) as { id?: unknown };
    if (typeof payload.id !== 'number') throw new Error('bad payload');
    return payload.id;
  } catch {
    throw unauthorized('is invalid');
  }
}

export function requireUserId(req: FastifyRequest): number {
  const id = userIdFromRequest(req);
  if (id === null) throw unauthorized('is missing');
  return id;
}
