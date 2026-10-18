export type Errors = Record<string, string[]>;

export class HttpError extends Error {
  constructor(
    public status: number,
    public errors: Errors,
  ) {
    super(JSON.stringify(errors));
  }
}

export const unauthorized = (msg = 'is missing') => new HttpError(401, { token: [msg] });
export const notFound = (resource: string) => new HttpError(404, { [resource]: ['not found'] });
export const forbidden = (resource: string) => new HttpError(403, { [resource]: ['forbidden'] });
export const unprocessable = (errors: Errors) => new HttpError(422, errors);
export const conflict = (errors: Errors) => new HttpError(409, errors);
