export type ErrorBody = Record<string, string[]>;

export class HttpError extends Error {
  constructor(public status: number, public errors: ErrorBody) {
    super(JSON.stringify(errors));
  }
}

export const unauthorized = (msg = 'is missing') => new HttpError(401, { token: [msg] });
export const notFound = (field: string) => new HttpError(404, { [field]: ['not found'] });
export const forbidden = (field: string) => new HttpError(403, { [field]: ['forbidden'] });
export const invalid = (errors: ErrorBody) => new HttpError(422, errors);

export function isBlank(v: unknown): boolean {
  return typeof v !== 'string' || v.trim() === '';
}
