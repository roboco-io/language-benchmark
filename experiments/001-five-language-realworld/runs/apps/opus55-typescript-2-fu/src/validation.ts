import { HttpError } from './errors';

export const BLANK = "can't be blank";
export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_LENGTH = 256;

type Json = Record<string, unknown>;

export function isObject(value: unknown): value is Json {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Returns `body[key]` as an object, or throws a 422 when the envelope is missing. */
export function envelope(body: unknown, key: string): Json {
  const inner = isObject(body) ? body[key] : undefined;
  if (!isObject(inner)) throw new HttpError(422, { [key]: [BLANK] });
  return inner;
}

/** True when the key was sent in the payload, even if its value is null. */
export function isPresent(obj: Json, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(obj, key);
}

export function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/** Returns a password-policy violation message (NIST 800-63B length rules), or undefined when valid. */
export function passwordProblem(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length === 0) return BLANK;
  if (value.length < MIN_PASSWORD_LENGTH) return `is too short (minimum is ${MIN_PASSWORD_LENGTH} characters)`;
  if (value.length > MAX_PASSWORD_LENGTH) return `is too long (maximum is ${MAX_PASSWORD_LENGTH} characters)`;
  return undefined;
}

/** Parses a non-negative integer query parameter, falling back to the default when absent or invalid. */
export function parseCount(value: unknown, fallback: number, max = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return fallback;
  return Math.min(Number(value), max);
}
