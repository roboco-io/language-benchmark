using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RealWorld;

public static class Security
{
    // PBKDF2-SHA256 password hashing, format: iterations.salt.hash (base64).
    private const int Iterations = 60_000;

    public static string HashPassword(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        var hash = Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA256, 32);
        return $"{Iterations}.{Convert.ToBase64String(salt)}.{Convert.ToBase64String(hash)}";
    }

    public static bool VerifyPassword(string password, string stored)
    {
        var parts = stored.Split('.');
        if (parts.Length != 3 || !int.TryParse(parts[0], out var iter)) return false;
        var salt = Convert.FromBase64String(parts[1]);
        var expected = Convert.FromBase64String(parts[2]);
        var actual = Rfc2898DeriveBytes.Pbkdf2(password, salt, iter, HashAlgorithmName.SHA256, expected.Length);
        return CryptographicOperations.FixedTimeEquals(actual, expected);
    }
}

public class JwtService(byte[] key)
{
    private static string B64(byte[] data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] UnB64(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        s = s.PadRight(s.Length + (4 - s.Length % 4) % 4, '=');
        return Convert.FromBase64String(s);
    }

    public string Create(int userId, TimeSpan lifetime)
    {
        var header = B64(Encoding.UTF8.GetBytes("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
        var exp = DateTimeOffset.UtcNow.Add(lifetime).ToUnixTimeSeconds();
        var payload = B64(Encoding.UTF8.GetBytes($"{{\"sub\":\"{userId}\",\"exp\":{exp}}}"));
        var sig = B64(HMACSHA256.HashData(key, Encoding.ASCII.GetBytes($"{header}.{payload}")));
        return $"{header}.{payload}.{sig}";
    }

    /// <summary>Returns the user id, or null when the token is malformed, forged or expired.</summary>
    public int? Validate(string token)
    {
        try
        {
            var parts = token.Split('.');
            if (parts.Length != 3) return null;
            var expected = HMACSHA256.HashData(key, Encoding.ASCII.GetBytes($"{parts[0]}.{parts[1]}"));
            if (!CryptographicOperations.FixedTimeEquals(expected, UnB64(parts[2]))) return null;
            using var doc = JsonDocument.Parse(UnB64(parts[1]));
            if (doc.RootElement.GetProperty("exp").GetInt64() < DateTimeOffset.UtcNow.ToUnixTimeSeconds()) return null;
            return int.Parse(doc.RootElement.GetProperty("sub").GetString()!);
        }
        catch
        {
            return null;
        }
    }
}
