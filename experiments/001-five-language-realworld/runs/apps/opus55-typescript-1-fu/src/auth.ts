import crypto from "node:crypto";
import { promisify } from "node:util";
import { createSigner, createVerifier } from "fast-jwt";
import type { FastifyRequest } from "fastify";
import { unauthorized } from "./errors.js";

const scrypt = promisify(crypto.scrypt) as (
  password: string,
  salt: Buffer,
  keylen: number,
) => Promise<Buffer>;

const KEY_LENGTH = 32;

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(password, salt, KEY_LENGTH);
  return `scrypt$${salt.toString("base64")}$${key.toString("base64")}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, saltB64, keyB64] = stored.split("$");
  if (scheme !== "scrypt" || !saltB64 || !keyB64) return false;
  const expected = Buffer.from(keyB64, "base64");
  const actual = await scrypt(password, Buffer.from(saltB64, "base64"), expected.length);
  return crypto.timingSafeEqual(expected, actual);
}

// The primary process guarantees JWT_SECRET is set before forking workers, so
// every worker signs and verifies tokens with the same key.
const secret = process.env.JWT_SECRET ?? "realworld-development-secret";
const sign = createSigner({ key: secret, algorithm: "HS256", expiresIn: 30 * 24 * 60 * 60 * 1000 });
const verify = createVerifier({ key: secret, algorithms: ["HS256"] });

export function signToken(userId: number): string {
  return sign({ sub: String(userId) });
}

function readToken(request: FastifyRequest): string | null {
  const header = request.headers.authorization;
  if (!header) return null;
  const match = /^(?:Token|Bearer)\s+(\S+)\s*$/i.exec(header);
  return match ? match[1] : "";
}

// Returns the authenticated user id, or null when no token was sent.
// Throws 401 when a token was sent but is malformed, expired or forged.
export function optionalUserId(request: FastifyRequest): number | null {
  const token = readToken(request);
  if (token === null) return null;
  try {
    const payload = verify(token) as { sub?: string };
    const id = Number(payload.sub);
    if (!Number.isInteger(id)) throw new Error("bad subject");
    return id;
  } catch {
    throw unauthorized("token", "is invalid");
  }
}

export function requireUserId(request: FastifyRequest): number {
  const id = optionalUserId(request);
  if (id === null) throw unauthorized("token", "is missing");
  return id;
}
