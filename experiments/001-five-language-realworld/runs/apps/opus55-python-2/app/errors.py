from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class APIError(Exception):
    """An error rendered as RealWorld's {"errors": {field: [messages]}} body."""

    def __init__(self, status_code: int, field: str, message: str):
        self.status_code = status_code
        self.errors = {field: [message]}


def not_found(field: str) -> APIError:
    return APIError(404, field, "not found")


def forbidden(field: str) -> APIError:
    return APIError(403, field, "forbidden")


def unprocessable(field: str, message: str) -> APIError:
    return APIError(422, field, message)


def register(app) -> None:
    @app.exception_handler(APIError)
    async def _api_error(_request: Request, exc: APIError):
        return JSONResponse({"errors": exc.errors}, status_code=exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_request: Request, exc: RequestValidationError):
        errors: dict[str, list[str]] = {}
        for err in exc.errors():
            loc = [str(p) for p in err.get("loc", ()) if p not in ("body", "query", "path")]
            field = loc[-1] if loc else "body"
            errors.setdefault(field, []).append(err.get("msg", "is invalid"))
        return JSONResponse({"errors": errors}, status_code=422)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_request: Request, exc: StarletteHTTPException):
        return JSONResponse({"errors": {"body": [str(exc.detail)]}}, status_code=exc.status_code)
