import crypto from 'node:crypto';
import { promisify } from 'node:util';
import jwt from 'jsonwebtoken';
import { unauthorized } from './errors';

const scrypt = promisify(crypto.scrypt) as (pw: string, salt: Buffer, len: number) => Promise<Buffer>;

const SECRET = process.env.JWT_SECRET || 'realworld-dev-secret-change-me';
const TOKEN_TTL_SECONDS = 30 * 24 * 3600;

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.randomBytes(16);
  const hash = await scrypt(password, salt, 64);
  return `${salt.toString('hex')}:${hash.toString('hex')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [saltHex, hashHex] = stored.split(':');
  if (!saltHex || !hashHex) return false;
  const expected = Buffer.from(hashHex, 'hex');
  const actual = await scrypt(password, Buffer.from(saltHex, 'hex'), expected.length);
  return crypto.timingSafeEqual(expected, actual);
}

export function signToken(userId: number): string {
  return jwt.sign({ sub: String(userId) }, SECRET, { algorithm: 'HS256', expiresIn: TOKEN_TTL_SECONDS });
}

function extractToken(header: string | undefined): string | null {
  if (!header) return null;
  const m = /^(?:Token|Bearer)\s+(.+)$/i.exec(header.trim());
  return m ? m[1] : null;
}

/** Returns the user id from a valid token, null when no token is sent. Throws 401 for a bad token. */
export function requireUserId(header: string | undefined): number {
  const token = extractToken(header);
  if (!token) throw unauthorized('is missing');
  const id = verifyToken(token);
  if (id === null) throw unauthorized('is invalid');
  return id;
}

/** For endpoints where authentication is optional: invalid or absent tokens mean "anonymous". */
export function optionalUserId(header: string | undefined): number | null {
  const token = extractToken(header);
  return token ? verifyToken(token) : null;
}

function verifyToken(token: string): number | null {
  try {
    const payload = jwt.verify(token, SECRET, { algorithms: ['HS256'] }) as jwt.JwtPayload;
    const id = Number(payload.sub);
    return Number.isInteger(id) ? id : null;
  } catch {
    return null;
  }
}

export { extractToken };
