using System.Text.Json;

namespace Conduit;

/// <summary>Helpers for RealWorld-style error bodies: {"errors": {"field": ["message"]}}.</summary>
public static class Errors
{
    public static IResult Of(int status, string field, string message) =>
        Results.Json(new { errors = new Dictionary<string, string[]> { [field] = [message] } }, statusCode: status);

    public static IResult Of(int status, Dictionary<string, List<string>> errors) =>
        Results.Json(new { errors }, statusCode: status);

    public static IResult Unauthorized(AuthState state) =>
        Of(401, "token", state == AuthState.Missing ? "is missing" : "is invalid");

    public static IResult NotFound(string field) => Of(404, field, "not found");
    public static IResult Forbidden(string field) => Of(403, field, "forbidden");
    public static IResult Blank(string field) => Of(422, field, "can't be blank");
}

/// <summary>
/// A request-body field. Distinguishes "absent" from explicit null, which PUT endpoints need.
/// </summary>
public readonly record struct Field(bool Present, JsonElement Value)
{
    public bool IsNull => Present && Value.ValueKind == JsonValueKind.Null;
    public bool IsString => Present && Value.ValueKind == JsonValueKind.String;
    public string? Str => IsString ? Value.GetString() : null;
    /// <summary>True when the field is present but not a non-empty string.</summary>
    public bool IsBlankOrInvalid => Present && (!IsString || string.IsNullOrWhiteSpace(Value.GetString()));
}

public sealed class Body(JsonElement root)
{
    public Field this[string name] =>
        root.TryGetProperty(name, out var v) ? new Field(true, v) : new Field(false, default);

    /// <summary>
    /// Parses the request body and returns the object nested under <paramref name="wrapper"/>,
    /// or null when the body is not valid JSON or the wrapper object is missing.
    /// </summary>
    public static async Task<Body?> Read(HttpContext ctx, string wrapper)
    {
        try
        {
            using var doc = await JsonDocument.ParseAsync(ctx.Request.Body, cancellationToken: ctx.RequestAborted);
            if (doc.RootElement.ValueKind == JsonValueKind.Object
                && doc.RootElement.TryGetProperty(wrapper, out var inner)
                && inner.ValueKind == JsonValueKind.Object)
                return new Body(inner.Clone());
        }
        catch (JsonException)
        {
        }
        return null;
    }

    public static IResult Invalid(string wrapper) => Errors.Of(422, wrapper, "can't be blank");
}

public static class Formats
{
    /// <summary>ISO 8601 UTC with millisecond precision, e.g. 2024-01-01T12:00:00.000Z.</summary>
    public static string Iso(DateTime d) =>
        DateTime.SpecifyKind(d, DateTimeKind.Utc).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

    /// <summary>Current UTC time truncated to milliseconds so stored and returned values match.</summary>
    public static DateTime Now()
    {
        var t = DateTime.UtcNow;
        return new DateTime(t.Ticks - t.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
    }
}
