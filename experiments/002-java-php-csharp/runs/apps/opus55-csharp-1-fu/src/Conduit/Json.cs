using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Conduit;

public record ProfileDto(string Username, string? Bio, string? Image, bool Following);

public record UserDto(string Email, string Token, string Username, string? Bio, string? Image);

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

/// <summary>Thrown for malformed requests; rendered as a 422 RealWorld error body.</summary>
public sealed class ValidationException(string field, string message) : Exception(message)
{
    public string Field { get; } = field;
}

public static class Api
{
    public static string Timestamp(DateTime value) =>
        DateTime.SpecifyKind(value, DateTimeKind.Utc).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

    public static IResult Error(int status, string field, string message) =>
        Results.Json(new { errors = new Dictionary<string, string[]> { [field] = [message] } }, statusCode: status);

    public static IResult Unauthorized(AuthState state) =>
        Error(401, "token", state == AuthState.Missing ? "is missing" : "is invalid");

    /// <summary>
    /// Reads the request body and returns the object wrapped under <paramref name="root"/>
    /// (e.g. {"user": {...}}). Malformed input raises <see cref="ValidationException"/>.
    /// </summary>
    public static async Task<JsonObject> ReadBody(HttpRequest request, string root)
    {
        JsonNode? node;
        try
        {
            node = await JsonNode.ParseAsync(request.Body);
        }
        catch (JsonException)
        {
            throw new ValidationException("body", "is invalid");
        }
        if (node is JsonObject obj && obj[root] is JsonObject inner) return inner;
        throw new ValidationException(root, "can't be blank");
    }

    /// <summary>
    /// Gets an optional string field. Returns (present, value); value is null when the JSON value is null.
    /// Non-string values are rejected.
    /// </summary>
    public static (bool Present, string? Value) Field(JsonObject obj, string name)
    {
        if (!obj.TryGetPropertyValue(name, out var node)) return (false, null);
        if (node is null) return (true, null);
        if (node is JsonValue v && v.GetValueKind() == JsonValueKind.String) return (true, v.GetValue<string>());
        throw new ValidationException(name, "must be a string");
    }

    /// <summary>Gets a field that must be a non-blank string.</summary>
    public static string Required(JsonObject obj, string name)
    {
        var (_, value) = Field(obj, name);
        if (string.IsNullOrWhiteSpace(value)) throw new ValidationException(name, "can't be blank");
        return value;
    }

    /// <summary>Gets an optional field that, when present, must be a non-blank string.</summary>
    public static string? OptionalNonBlank(JsonObject obj, string name)
    {
        var (present, value) = Field(obj, name);
        if (!present) return null;
        if (string.IsNullOrWhiteSpace(value)) throw new ValidationException(name, "can't be blank");
        return value;
    }

    /// <summary>Parses a tagList field: null when absent; rejects null or non-string entries.</summary>
    public static List<string>? Tags(JsonObject obj)
    {
        if (!obj.TryGetPropertyValue("tagList", out var node)) return null;
        if (node is not JsonArray array) throw new ValidationException("tagList", "must be an array of strings");
        var tags = new List<string>();
        foreach (var item in array)
        {
            if (item is not JsonValue v || v.GetValueKind() != JsonValueKind.String)
                throw new ValidationException("tagList", "must be an array of strings");
            var tag = v.GetValue<string>().Trim();
            if (tag.Length > 0 && !tags.Contains(tag)) tags.Add(tag);
        }
        return tags;
    }
}
