// Errors are rendered in the RealWorld format: {"errors": {"<field>": ["<message>"]}}.
export class HttpError extends Error {
  constructor(
    readonly statusCode: number,
    readonly errors: Record<string, string[]>,
  ) {
    super(Object.entries(errors).map(([key, messages]) => `${key} ${messages.join(", ")}`).join("; "));
  }
}

export const unprocessable = (field: string, message: string) =>
  new HttpError(422, { [field]: [message] });
export const unauthorized = (field: string, message: string) =>
  new HttpError(401, { [field]: [message] });
export const forbidden = (field: string) => new HttpError(403, { [field]: ["forbidden"] });
export const notFound = (field: string) => new HttpError(404, { [field]: ["not found"] });
export const conflict = (field: string) => new HttpError(409, { [field]: ["has already been taken"] });
