import { HttpError, Validator } from './errors';

type Body = Record<string, unknown>;

// Extracts the wrapper object (for example "user" in { "user": { ... } }) or rejects with 422.
export function wrapper(body: unknown, key: string): Body {
  const value = (body as Body | null | undefined)?.[key];
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new HttpError(422, { body: [`${key} is required`] });
  }
  return value as Body;
}

// Validates a required, non-empty string field. Returns the trimmed value when it is valid.
export function requiredString(v: Validator, input: Body, field: string): string | undefined {
  const value = input[field];
  if (value === undefined || value === null || (typeof value === 'string' && value.trim() === '')) {
    v.add(field, "can't be blank");
    return undefined;
  }
  if (typeof value !== 'string') {
    v.add(field, 'must be a string');
    return undefined;
  }
  return value.trim();
}

// Validates an optional field that, when present, must be a non-empty string.
export function optionalNonEmptyString(v: Validator, input: Body, field: string): string | undefined {
  if (!(field in input)) return undefined;
  return requiredString(v, input, field);
}

// Validates an optional nullable field. Empty strings are normalized to null.
// Returns undefined when the field is absent, so callers can skip the update.
export function optionalNullableString(v: Validator, input: Body, field: string): string | null | undefined {
  if (!(field in input)) return undefined;
  const value = input[field];
  if (value === null) return null;
  if (typeof value !== 'string') {
    v.add(field, 'must be a string');
    return undefined;
  }
  return value.trim() === '' ? null : value;
}

export const MIN_PASSWORD_LENGTH = 8;

// Password policy based on NIST SP 800-63B: at least 8 characters, no composition rules.
export function password(v: Validator, input: Body, field = 'password'): string | undefined {
  const value = input[field];
  if (value === undefined || value === null || value === '') {
    v.add(field, "can't be blank");
    return undefined;
  }
  if (typeof value !== 'string') {
    v.add(field, 'must be a string');
    return undefined;
  }
  if ([...value].length < MIN_PASSWORD_LENGTH) {
    v.add(field, `is too short (minimum is ${MIN_PASSWORD_LENGTH} characters)`);
    return undefined;
  }
  return value;
}

// Validates a tag list. Duplicates and blank entries are removed; order is preserved.
export function tagList(v: Validator, input: Body, field = 'tagList'): string[] | undefined {
  if (!(field in input)) return undefined;
  const value = input[field];
  if (!Array.isArray(value) || value.some((t) => typeof t !== 'string')) {
    v.add(field, 'must be an array of strings');
    return undefined;
  }
  const tags: string[] = [];
  for (const raw of value as string[]) {
    const tag = raw.trim();
    if (tag !== '' && !tags.includes(tag)) tags.push(tag);
  }
  return tags;
}
