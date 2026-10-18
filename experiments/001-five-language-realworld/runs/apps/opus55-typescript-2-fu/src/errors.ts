export type ErrorMap = Record<string, string[]>;

/** An error that maps directly to a RealWorld-style `{ errors: { field: [messages] } }` response. */
export class HttpError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly errors: ErrorMap,
  ) {
    super(Object.entries(errors).map(([k, v]) => `${k} ${v.join(', ')}`).join('; '));
  }
}

export const unauthorized = (message = 'is missing') => new HttpError(401, { token: [message] });
export const forbidden = (resource: string) => new HttpError(403, { [resource]: ['forbidden'] });
export const notFound = (resource: string) => new HttpError(404, { [resource]: ['not found'] });
export const conflict = (field: string) => new HttpError(409, { [field]: ['has already been taken'] });
export const unprocessable = (field: string, message: string) => new HttpError(422, { [field]: [message] });

/** Collects validation messages for several fields and throws them as a single 422. */
export class Validator {
  private readonly errors: ErrorMap = {};

  add(field: string, message: string): void {
    (this.errors[field] ??= []).push(message);
  }

  throwIfAny(): void {
    if (Object.keys(this.errors).length > 0) throw new HttpError(422, this.errors);
  }
}
