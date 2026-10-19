using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;
using Microsoft.Data.Sqlite;
using RealWorld.Data;

namespace RealWorld.Infrastructure;

/// <summary>Thrown by endpoint code to short-circuit with a RealWorld-style error response.</summary>
public sealed class ApiException(int status, Dictionary<string, string[]> errors) : Exception
{
    public int Status { get; } = status;
    public Dictionary<string, string[]> Errors { get; } = errors;

    public static ApiException Of(int status, string field, string message) => new(status, new() { [field] = [message] });
    public static ApiException NotFound(string field) => Of(404, field, "not found");
    public static ApiException Forbidden(string field) => Of(403, field, "forbidden");
    public static ApiException Unauthorized(string message) => Of(401, "token", message);
}

/// <summary>Accumulates field validation errors and throws a single 422 response.</summary>
public sealed class Validation
{
    private readonly Dictionary<string, List<string>> _errors = [];

    public void Add(string field, string message)
    {
        if (!_errors.TryGetValue(field, out var list)) _errors[field] = list = [];
        list.Add(message);
    }

    public void ThrowIfAny()
    {
        if (_errors.Count > 0)
            throw new ApiException(422, _errors.ToDictionary(kv => kv.Key, kv => kv.Value.ToArray()));
    }
}

public static class Api
{
    public const string Blank = "can't be blank";

    public static IResult Error(int status, Dictionary<string, string[]> errors) =>
        Results.Json(new { errors }, statusCode: status);

    /// <summary>Resolves the caller from "Authorization: Token &lt;jwt&gt;" (also accepts "Bearer").</summary>
    public static int? OptionalUser(HttpContext ctx)
    {
        string? header = ctx.Request.Headers.Authorization;
        if (string.IsNullOrWhiteSpace(header)) return null;
        var space = header.IndexOf(' ');
        var scheme = space < 0 ? header : header[..space];
        var token = space < 0 ? "" : header[(space + 1)..].Trim();
        if (!scheme.Equals("Token", StringComparison.OrdinalIgnoreCase) &&
            !scheme.Equals("Bearer", StringComparison.OrdinalIgnoreCase))
            throw ApiException.Unauthorized("is invalid");
        if (token.Length == 0) throw ApiException.Unauthorized("is missing");
        var jwt = ctx.RequestServices.GetRequiredService<Jwt>();
        return jwt.Validate(token) ?? throw ApiException.Unauthorized("is invalid");
    }

    public static async Task<int> RequiredUser(HttpContext ctx, AppDb db)
    {
        var id = OptionalUser(ctx) ?? throw ApiException.Unauthorized("is missing");
        if (!await db.Users.AnyAsync(u => u.Id == id)) throw ApiException.Unauthorized("is invalid");
        return id;
    }

    /// <summary>Reads the request body and returns the object stored under the given root key.</summary>
    public static async Task<JsonObject> ReadRoot(HttpContext ctx, string rootKey)
    {
        JsonNode? node;
        try
        {
            node = await JsonNode.ParseAsync(ctx.Request.Body);
        }
        catch (JsonException)
        {
            throw ApiException.Of(422, "body", "is not valid JSON");
        }
        if (node is JsonObject obj && obj[rootKey] is JsonObject inner) return inner;
        throw ApiException.Of(422, rootKey, Blank);
    }

    /// <summary>Returns true when the property is present; value is null for JSON null or non-string values.</summary>
    public static bool TryGetString(JsonObject obj, string key, out string? value, out bool isString)
    {
        value = null;
        isString = false;
        if (!obj.TryGetPropertyValue(key, out var node)) return false;
        if (node is JsonValue v && v.GetValueKind() == JsonValueKind.String)
        {
            value = v.GetValue<string>();
            isString = true;
        }
        return true;
    }

    /// <summary>Gets a required non-blank string, recording a validation error otherwise.</summary>
    public static string? RequiredString(JsonObject obj, string key, Validation v)
    {
        TryGetString(obj, key, out var value, out _);
        if (string.IsNullOrWhiteSpace(value))
        {
            v.Add(key, Blank);
            return null;
        }
        return value;
    }

    public static bool IsUniqueViolation(DbUpdateException e) =>
        e.InnerException is SqliteException { SqliteErrorCode: 19 } se &&
        se.Message.Contains("UNIQUE", StringComparison.OrdinalIgnoreCase);

    public static string FormatDate(DateTime d) =>
        DateTime.SpecifyKind(d, DateTimeKind.Utc).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

    /// <summary>Current UTC time truncated to milliseconds so stored and returned values match.</summary>
    public static DateTime Now()
    {
        var t = DateTime.UtcNow;
        return new DateTime(t.Ticks - t.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
    }
}

public record ProfileDto(string Username, string? Bio, string? Image, bool Following);

public record ArticleDto(
    string Slug,
    string Title,
    string Description,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? Body,
    List<string> TagList,
    string CreatedAt,
    string UpdatedAt,
    bool Favorited,
    int FavoritesCount,
    ProfileDto Author);

public record CommentDto(int Id, string CreatedAt, string UpdatedAt, string Body, ProfileDto Author);

public record UserDto(string Email, string Token, string Username, string? Bio, string? Image);
