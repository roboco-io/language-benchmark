import crypto from 'node:crypto';
import { promisify } from 'node:util';
import jwt from 'jsonwebtoken';
import type { FastifyRequest } from 'fastify';
import { unauthorized } from './errors';

const scrypt = promisify(crypto.scrypt) as (password: string, salt: Buffer, keylen: number) => Promise<Buffer>;
const KEY_LENGTH = 32;
const TOKEN_TTL = '30d';

function secret(): string {
  const value = process.env.JWT_SECRET;
  if (!value) throw new Error('JWT_SECRET is not set');
  return value;
}

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.randomBytes(16);
  const hash = await scrypt(password, salt, KEY_LENGTH);
  return `scrypt$${salt.toString('base64')}$${hash.toString('base64')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, saltB64, hashB64] = stored.split('$');
  if (scheme !== 'scrypt' || !saltB64 || !hashB64) return false;
  const expected = Buffer.from(hashB64, 'base64');
  const actual = await scrypt(password, Buffer.from(saltB64, 'base64'), expected.length);
  return crypto.timingSafeEqual(expected, actual);
}

export function signToken(userId: number): string {
  return jwt.sign({ sub: String(userId) }, secret(), { algorithm: 'HS256', expiresIn: TOKEN_TTL });
}

/** Returns the raw token from an `Authorization: Token <jwt>` (or `Bearer <jwt>`) header, if any. */
function extractToken(request: FastifyRequest): string | undefined {
  const header = request.headers.authorization;
  if (!header) return undefined;
  const match = /^(?:Token|Bearer)\s+(\S+)\s*$/i.exec(header);
  if (!match) throw unauthorized('is invalid');
  return match[1];
}

/** Returns the authenticated user id, or undefined when no token was sent. Invalid tokens are rejected. */
export function optionalUserId(request: FastifyRequest): number | undefined {
  const token = extractToken(request);
  if (token === undefined) return undefined;
  try {
    const payload = jwt.verify(token, secret(), { algorithms: ['HS256'] });
    const id = typeof payload === 'object' ? Number(payload.sub) : NaN;
    if (!Number.isInteger(id)) throw new Error('bad subject');
    return id;
  } catch {
    throw unauthorized('is invalid');
  }
}

export function requireUserId(request: FastifyRequest): number {
  const id = optionalUserId(request);
  if (id === undefined) throw unauthorized('is missing');
  return id;
}
