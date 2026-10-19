using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RealWorld.Infrastructure;

/// <summary>
/// Minimal HS256 JWT issuer/validator. The token carries the user id in "sub".
/// </summary>
public sealed class Jwt
{
    private static readonly string HeaderSegment = Base64Url(Encoding.UTF8.GetBytes("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
    private readonly byte[] _key;
    private readonly TimeSpan _lifetime = TimeSpan.FromDays(30);

    public Jwt(string secret)
    {
        _key = SHA256.HashData(Encoding.UTF8.GetBytes(secret));
    }

    public string Issue(int userId)
    {
        var now = DateTimeOffset.UtcNow;
        var payload = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
        {
            ["sub"] = userId.ToString(),
            ["iat"] = now.ToUnixTimeSeconds(),
            ["exp"] = now.Add(_lifetime).ToUnixTimeSeconds(),
        });
        var unsigned = HeaderSegment + "." + Base64Url(payload);
        return unsigned + "." + Base64Url(Sign(unsigned));
    }

    /// <summary>Returns the user id if the token is well-formed, correctly signed and unexpired.</summary>
    public int? Validate(string token)
    {
        var parts = token.Split('.');
        if (parts.Length != 3 || parts[0] != HeaderSegment) return null;
        try
        {
            var expected = Sign(parts[0] + "." + parts[1]);
            var actual = FromBase64Url(parts[2]);
            if (!CryptographicOperations.FixedTimeEquals(expected, actual)) return null;

            using var doc = JsonDocument.Parse(FromBase64Url(parts[1]));
            var root = doc.RootElement;
            if (!root.TryGetProperty("exp", out var exp) || exp.GetInt64() < DateTimeOffset.UtcNow.ToUnixTimeSeconds())
                return null;
            if (root.TryGetProperty("sub", out var sub) && int.TryParse(sub.GetString(), out var id))
                return id;
            return null;
        }
        catch (Exception e) when (e is FormatException or JsonException or InvalidOperationException)
        {
            return null;
        }
    }

    private byte[] Sign(string data) => HMACSHA256.HashData(_key, Encoding.ASCII.GetBytes(data));

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromBase64Url(string s)
    {
        var b64 = s.Replace('-', '+').Replace('_', '/');
        b64 += (b64.Length % 4) switch { 2 => "==", 3 => "=", _ => "" };
        return Convert.FromBase64String(b64);
    }
}
