import { randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import jwt from 'jsonwebtoken';
import type { FastifyRequest } from 'fastify';
import { unauthorized } from './errors';

const scrypt = promisify(scryptCb) as (password: string, salt: Buffer, keylen: number) => Promise<Buffer>;

const JWT_SECRET = process.env.JWT_SECRET ?? 'realworld-development-secret-change-me';
const JWT_EXPIRES_IN = '30d';
const KEY_LENGTH = 64;

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const key = await scrypt(password, salt, KEY_LENGTH);
  return `${salt.toString('hex')}:${key.toString('hex')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [saltHex, keyHex] = stored.split(':');
  if (!saltHex || !keyHex) return false;
  const expected = Buffer.from(keyHex, 'hex');
  const actual = await scrypt(password, Buffer.from(saltHex, 'hex'), expected.length);
  return timingSafeEqual(expected, actual);
}

export function signToken(user: { id: number; username: string }): string {
  return jwt.sign({ sub: String(user.id), username: user.username }, JWT_SECRET, { expiresIn: JWT_EXPIRES_IN });
}

// Returns the user id from the Authorization header, or null when no header is sent.
// Accepts both "Token <jwt>" (RealWorld spec) and "Bearer <jwt>".
// Throws 401 when a header is present but the token is invalid.
export function readUserId(request: FastifyRequest): number | null {
  const header = request.headers.authorization;
  if (!header) return null;
  const match = /^(?:Token|Bearer)\s+(\S+)$/i.exec(header.trim());
  if (!match) throw unauthorized('is invalid');
  try {
    const payload = jwt.verify(match[1], JWT_SECRET) as jwt.JwtPayload;
    const id = Number(payload.sub);
    if (!Number.isInteger(id)) throw new Error('bad subject');
    return id;
  } catch {
    throw unauthorized('is invalid');
  }
}

export function requireUserId(request: FastifyRequest): number {
  const id = readUserId(request);
  if (id === null) throw unauthorized('is missing');
  return id;
}
