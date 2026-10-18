// Error responses follow the RealWorld format: { "errors": { "<field>": ["<message>"] } }.
export class HttpError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly errors: Record<string, string[]>,
  ) {
    super(Object.entries(errors).map(([k, v]) => `${k} ${v.join(', ')}`).join('; '));
  }
}

export const unauthorized = (message = 'is missing') => new HttpError(401, { token: [message] });
export const notFound = (resource: string) => new HttpError(404, { [resource]: ['not found'] });
export const forbidden = (resource: string) => new HttpError(403, { [resource]: ['forbidden'] });
export const conflict = (field: string) => new HttpError(409, { [field]: ['has already been taken'] });

// Collects field validation errors and throws a single 422 response.
export class Validator {
  private readonly errors: Record<string, string[]> = {};

  add(field: string, message: string): void {
    (this.errors[field] ??= []).push(message);
  }

  throwIfInvalid(): void {
    if (Object.keys(this.errors).length > 0) throw new HttpError(422, this.errors);
  }
}
