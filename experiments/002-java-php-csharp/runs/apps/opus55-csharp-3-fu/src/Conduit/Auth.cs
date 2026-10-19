using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Conduit;

/// <summary>
/// Minimal HS256 JWT issuer/validator. The token carries the user id in the "sub" claim.
/// </summary>
public class JwtService(byte[] key)
{
    private static readonly TimeSpan Lifetime = TimeSpan.FromDays(30);

    public string Issue(int userId)
    {
        var now = DateTimeOffset.UtcNow;
        var header = B64(Encoding.UTF8.GetBytes("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
        var payload = B64(JsonSerializer.SerializeToUtf8Bytes(new
        {
            sub = userId.ToString(),
            iat = now.ToUnixTimeSeconds(),
            exp = now.Add(Lifetime).ToUnixTimeSeconds(),
            jti = Guid.NewGuid().ToString("N"),
        }));
        var signingInput = header + "." + payload;
        return signingInput + "." + B64(HMACSHA256.HashData(key, Encoding.ASCII.GetBytes(signingInput)));
    }

    /// <summary>Returns the user id if the token is well-formed, correctly signed and not expired.</summary>
    public int? Validate(string token)
    {
        var parts = token.Split('.');
        if (parts.Length != 3) return null;
        try
        {
            var expected = HMACSHA256.HashData(key, Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]));
            if (!CryptographicOperations.FixedTimeEquals(expected, FromB64(parts[2]))) return null;
            using var doc = JsonDocument.Parse(FromB64(parts[1]));
            var root = doc.RootElement;
            if (!root.TryGetProperty("exp", out var exp) || exp.GetInt64() < DateTimeOffset.UtcNow.ToUnixTimeSeconds()) return null;
            if (!root.TryGetProperty("sub", out var sub) || !int.TryParse(sub.GetString(), out var id)) return null;
            return id;
        }
        catch (Exception e) when (e is FormatException or JsonException or InvalidOperationException)
        {
            return null;
        }
    }

    private static string B64(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromB64(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(s.PadRight(s.Length + (4 - s.Length % 4) % 4, '='));
    }
}

/// <summary>PBKDF2-SHA256 password hashing. Format: iterations.salt.hash (base64).</summary>
public static class Passwords
{
    private const int Iterations = 100_000;

    public static string Hash(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        var hash = Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA256, 32);
        return $"{Iterations}.{Convert.ToBase64String(salt)}.{Convert.ToBase64String(hash)}";
    }

    public static bool Verify(string password, string stored)
    {
        var parts = stored.Split('.');
        if (parts.Length != 3 || !int.TryParse(parts[0], out var iterations)) return false;
        var salt = Convert.FromBase64String(parts[1]);
        var expected = Convert.FromBase64String(parts[2]);
        var actual = Rfc2898DeriveBytes.Pbkdf2(password, salt, iterations, HashAlgorithmName.SHA256, expected.Length);
        return CryptographicOperations.FixedTimeEquals(actual, expected);
    }
}

public enum AuthState { Missing, Invalid, Valid }

public static class AuthExtensions
{
    /// <summary>Reads "Authorization: Token &lt;jwt&gt;" (or "Bearer &lt;jwt&gt;") and validates it.</summary>
    public static (AuthState State, int? UserId) Authenticate(this HttpContext ctx)
    {
        var header = ctx.Request.Headers.Authorization.ToString();
        if (string.IsNullOrWhiteSpace(header)) return (AuthState.Missing, null);
        var space = header.IndexOf(' ');
        if (space < 0) return (AuthState.Invalid, null);
        var scheme = header[..space];
        if (!scheme.Equals("Token", StringComparison.OrdinalIgnoreCase) && !scheme.Equals("Bearer", StringComparison.OrdinalIgnoreCase))
            return (AuthState.Invalid, null);
        var id = ctx.RequestServices.GetRequiredService<JwtService>().Validate(header[(space + 1)..].Trim());
        return id is null ? (AuthState.Invalid, null) : (AuthState.Valid, id);
    }
}
