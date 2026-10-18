export type ErrorBody = Record<string, string[]>;

export class HttpError extends Error {
  constructor(public status: number, public errors: ErrorBody) {
    super(JSON.stringify(errors));
  }
}

export const unauthorized = (msg = 'is missing') => new HttpError(401, { token: [msg] });
export const forbidden = (field: string) => new HttpError(403, { [field]: ['forbidden'] });
export const notFound = (field: string) => new HttpError(404, { [field]: ['not found'] });
export const invalid = (field: string, msg: string) => new HttpError(422, { [field]: [msg] });
