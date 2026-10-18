import jwt from 'jsonwebtoken';
import type { FastifyRequest } from 'fastify';
import { unauthorized } from './errors';

const SECRET = process.env.JWT_SECRET ?? 'realworld-dev-secret-change-me';

export const signToken = (userId: number): string =>
  jwt.sign({ id: userId }, SECRET, { expiresIn: '30d' });

function extractToken(req: FastifyRequest): string | null {
  const header = req.headers.authorization;
  if (!header) return null;
  const m = /^(?:Token|Bearer)\s+(.+)$/i.exec(header.trim());
  return m ? m[1] : null;
}

/** Returns the user id from the Authorization header, or null when absent. Throws 401 on a bad token. */
export function optionalUserId(req: FastifyRequest): number | null {
  const token = extractToken(req);
  if (!token) return null;
  try {
    const payload = jwt.verify(token, SECRET) as { id?: number };
    if (typeof payload.id !== 'number') throw new Error('bad payload');
    return payload.id;
  } catch {
    throw unauthorized('is invalid');
  }
}

export function requireUserId(req: FastifyRequest): number {
  const id = optionalUserId(req);
  if (id === null) throw unauthorized('is missing');
  return id;
}
