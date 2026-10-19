using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RealWorld.Api;

public sealed class Security(string secret)
{
    private readonly byte[] _key = Encoding.UTF8.GetBytes(secret);

    public static string HashPassword(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        var hash = Rfc2898DeriveBytes.Pbkdf2(password, salt, 100_000, HashAlgorithmName.SHA256, 32);
        return $"{Convert.ToBase64String(salt)}.{Convert.ToBase64String(hash)}";
    }

    public static bool VerifyPassword(string password, string stored)
    {
        var parts = stored.Split('.');
        if (parts.Length != 2) return false;
        var salt = Convert.FromBase64String(parts[0]);
        var expected = Convert.FromBase64String(parts[1]);
        var actual = Rfc2898DeriveBytes.Pbkdf2(password, salt, 100_000, HashAlgorithmName.SHA256, 32);
        return CryptographicOperations.FixedTimeEquals(expected, actual);
    }

    private static string B64(byte[] data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] FromB64(string s)
    {
        s = s.Replace('-', '+').Replace('_', '/');
        s = s.PadRight(s.Length + (4 - s.Length % 4) % 4, '=');
        return Convert.FromBase64String(s);
    }

    public string CreateToken(User user)
    {
        var header = B64(Encoding.UTF8.GetBytes("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
        var exp = DateTimeOffset.UtcNow.AddDays(7).ToUnixTimeSeconds();
        var payload = B64(Encoding.UTF8.GetBytes(
            JsonSerializer.Serialize(new { sub = user.Id, username = user.Username, exp })));
        var sig = B64(HMACSHA256.HashData(_key, Encoding.ASCII.GetBytes($"{header}.{payload}")));
        return $"{header}.{payload}.{sig}";
    }

    /// <summary>Returns the user id from a valid, unexpired token, or null.</summary>
    public int? ValidateToken(string token)
    {
        try
        {
            var parts = token.Split('.');
            if (parts.Length != 3) return null;
            var expected = HMACSHA256.HashData(_key, Encoding.ASCII.GetBytes($"{parts[0]}.{parts[1]}"));
            if (!CryptographicOperations.FixedTimeEquals(expected, FromB64(parts[2]))) return null;
            using var doc = JsonDocument.Parse(FromB64(parts[1]));
            var root = doc.RootElement;
            if (root.GetProperty("exp").GetInt64() < DateTimeOffset.UtcNow.ToUnixTimeSeconds()) return null;
            return root.GetProperty("sub").GetInt32();
        }
        catch
        {
            return null;
        }
    }
}
