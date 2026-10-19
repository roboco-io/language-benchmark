using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Identity;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;

namespace Conduit;

public enum AuthState { Missing, Invalid, Valid }

public sealed class TokenService
{
    private readonly JsonWebTokenHandler _handler = new();
    private readonly SigningCredentials _credentials;
    private readonly TokenValidationParameters _validation;
    private readonly TimeSpan _lifetime = TimeSpan.FromDays(30);

    public TokenService(string secret)
    {
        // HMAC-SHA256 needs a key of at least 256 bits; stretch short secrets deterministically.
        var keyBytes = System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(secret));
        var key = new SymmetricSecurityKey(keyBytes);
        _credentials = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);
        _validation = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = "conduit",
            ValidateAudience = false,
            ValidateLifetime = true,
            IssuerSigningKey = key,
            ClockSkew = TimeSpan.FromMinutes(1),
        };
    }

    public string Create(User user)
    {
        var now = DateTime.UtcNow;
        return _handler.CreateToken(new SecurityTokenDescriptor
        {
            Issuer = "conduit",
            Subject = new ClaimsIdentity([new Claim(JwtRegisteredClaimNames.Sub, user.Id.ToString())]),
            IssuedAt = now,
            NotBefore = now,
            Expires = now.Add(_lifetime),
            SigningCredentials = _credentials,
        });
    }

    /// <summary>Reads the "Authorization: Token &lt;jwt&gt;" header (also accepts "Bearer").</summary>
    public async Task<(AuthState State, int UserId)> ReadAsync(HttpRequest request)
    {
        string? header = request.Headers.Authorization;
        if (string.IsNullOrWhiteSpace(header)) return (AuthState.Missing, 0);

        var parts = header.Trim().Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 2 ||
            !(parts[0].Equals("Token", StringComparison.OrdinalIgnoreCase) ||
              parts[0].Equals("Bearer", StringComparison.OrdinalIgnoreCase)))
            return (AuthState.Invalid, 0);

        var result = await _handler.ValidateTokenAsync(parts[1].Trim(), _validation);
        if (!result.IsValid) return (AuthState.Invalid, 0);
        if (!result.Claims.TryGetValue(JwtRegisteredClaimNames.Sub, out var sub) ||
            !int.TryParse(sub?.ToString(), out var id))
            return (AuthState.Invalid, 0);
        return (AuthState.Valid, id);
    }
}

public static class Passwords
{
    private static readonly PasswordHasher<User> Hasher = new();

    public static string Hash(string password) => Hasher.HashPassword(null!, password);

    public static bool Verify(string hash, string password) =>
        Hasher.VerifyHashedPassword(null!, hash, password) != PasswordVerificationResult.Failed;
}
