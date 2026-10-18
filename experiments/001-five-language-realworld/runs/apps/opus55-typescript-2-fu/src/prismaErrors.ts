import { Prisma } from '@prisma/client';

/** True when the error is a unique-constraint violation, optionally on a specific column. */
export function isUniqueViolation(error: unknown, field?: string): boolean {
  if (!(error instanceof Prisma.PrismaClientKnownRequestError) || error.code !== 'P2002') return false;
  if (!field) return true;
  const target = error.meta?.target;
  const fields = Array.isArray(target) ? target.map(String) : typeof target === 'string' ? [target] : [];
  return fields.length === 0 || fields.some((f) => f === field || f.includes(field));
}

/** True when the error means the row to update or delete no longer exists. */
export function isRecordNotFound(error: unknown): boolean {
  return error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2025';
}
