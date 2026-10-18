import { unprocessable } from "./errors.js";

type Json = Record<string, unknown>;

// Extracts the wrapper object of a RealWorld request body, e.g. body.user.
export function wrapper(body: unknown, key: string): Json {
  const value = (body as Json | null | undefined)?.[key];
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw unprocessable(key, "can't be blank");
  }
  return value as Json;
}

// A required string must be present, a string, and not blank.
export function requiredString(input: Json, field: string): string {
  const value = input[field];
  if (typeof value !== "string" || value.trim() === "") {
    throw unprocessable(field, "can't be blank");
  }
  return value;
}

// An optional field that cannot be cleared: absent means "keep the current
// value", while null or a blank string is rejected.
export function optionalNonBlankString(input: Json, field: string): string | undefined {
  if (!(field in input) || input[field] === undefined) return undefined;
  return requiredString(input, field);
}

// A nullable field: absent means "keep", null or a blank string means "clear".
export function optionalNullableString(input: Json, field: string): string | null | undefined {
  if (!(field in input) || input[field] === undefined) return undefined;
  const value = input[field];
  if (value === null) return null;
  if (typeof value !== "string") throw unprocessable(field, "is invalid");
  return value.trim() === "" ? null : value;
}

export function optionalTagList(input: Json, field = "tagList"): string[] | undefined {
  if (!(field in input) || input[field] === undefined) return undefined;
  const value = input[field];
  if (!Array.isArray(value) || value.some((tag) => typeof tag !== "string")) {
    throw unprocessable(field, "must be an array of strings");
  }
  const tags: string[] = [];
  for (const raw of value as string[]) {
    const tag = raw.trim();
    if (tag !== "" && !tags.includes(tag)) tags.push(tag);
  }
  return tags;
}

export const MIN_PASSWORD_LENGTH = 8;

export function checkPassword(password: string): void {
  if (password.length < MIN_PASSWORD_LENGTH) {
    throw unprocessable("password", `is too short (minimum is ${MIN_PASSWORD_LENGTH} characters)`);
  }
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/;

export function checkEmail(email: string): void {
  if (!EMAIL_PATTERN.test(email)) throw unprocessable("email", "is invalid");
}

// Parses a non-negative integer query parameter, falling back to a default.
export function queryInt(value: unknown, fallback: number, max = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== "string" || !/^\d+$/.test(value)) return fallback;
  return Math.min(Number.parseInt(value, 10), max);
}
