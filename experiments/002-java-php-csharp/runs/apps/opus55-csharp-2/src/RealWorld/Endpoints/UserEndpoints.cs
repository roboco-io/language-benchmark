using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using RealWorld.Data;
using RealWorld.Infrastructure;

namespace RealWorld.Endpoints;

public static class UserEndpoints
{
    private const int MinPasswordLength = 8;
    private static readonly PasswordHasher<User> Hasher = new();

    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapPost("/api/users", Register);
        app.MapPost("/api/users/login", Login);
        app.MapGet("/api/user", GetCurrent);
        app.MapPut("/api/user", Update);

        app.MapGet("/api/profiles/{username}", GetProfile);
        app.MapPost("/api/profiles/{username}/follow", Follow);
        app.MapDelete("/api/profiles/{username}/follow", Unfollow);
    }

    private static UserDto ToDto(User u, Jwt jwt) => new(u.Email, jwt.Issue(u.Id), u.Username, u.Bio, u.Image);

    private static void CheckPassword(string? password, Validation v)
    {
        if (string.IsNullOrEmpty(password)) v.Add("password", Api.Blank);
        else if (password.Length < MinPasswordLength) v.Add("password", $"is too short (minimum is {MinPasswordLength} characters)");
    }

    private static async Task EnsureUnique(AppDb db, string? username, string? email, int? exceptId)
    {
        var errors = new Dictionary<string, string[]>();
        if (username != null && await db.Users.AnyAsync(u => u.Username == username && u.Id != exceptId))
            errors["username"] = ["has already been taken"];
        if (email != null && await db.Users.AnyAsync(u => u.Email == email && u.Id != exceptId))
            errors["email"] = ["has already been taken"];
        if (errors.Count > 0) throw new ApiException(409, errors);
    }

    private static async Task SaveUser(AppDb db, User user)
    {
        try
        {
            await db.SaveChangesAsync();
        }
        catch (DbUpdateException e) when (Api.IsUniqueViolation(e))
        {
            // Lost a race with a concurrent request; report which field collided.
            db.Entry(user).State = EntityState.Detached;
            await EnsureUnique(db, user.Username, user.Email, user.Id == 0 ? null : user.Id);
            throw ApiException.Of(409, "username", "has already been taken");
        }
    }

    private static async Task<IResult> Register(HttpContext ctx, AppDb db, Jwt jwt)
    {
        var body = await Api.ReadRoot(ctx, "user");
        var v = new Validation();
        var username = Api.RequiredString(body, "username", v)?.Trim();
        var email = Api.RequiredString(body, "email", v)?.Trim();
        Api.TryGetString(body, "password", out var password, out _);
        CheckPassword(password, v);
        v.ThrowIfAny();

        await EnsureUnique(db, username, email, null);
        var user = new User { Username = username!, Email = email!, PasswordHash = "" };
        user.PasswordHash = Hasher.HashPassword(user, password!);
        db.Users.Add(user);
        await SaveUser(db, user);
        return Results.Json(new { user = ToDto(user, jwt) }, statusCode: 201);
    }

    private static async Task<IResult> Login(HttpContext ctx, AppDb db, Jwt jwt)
    {
        var body = await Api.ReadRoot(ctx, "user");
        var v = new Validation();
        var email = Api.RequiredString(body, "email", v)?.Trim();
        Api.TryGetString(body, "password", out var password, out _);
        if (string.IsNullOrEmpty(password)) v.Add("password", Api.Blank);
        v.ThrowIfAny();

        var user = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Email == email);
        if (user == null || Hasher.VerifyHashedPassword(user, user.PasswordHash, password!) == PasswordVerificationResult.Failed)
            throw ApiException.Of(401, "credentials", "invalid");
        return Results.Ok(new { user = ToDto(user, jwt) });
    }

    private static async Task<IResult> GetCurrent(HttpContext ctx, AppDb db, Jwt jwt)
    {
        var id = await Api.RequiredUser(ctx, db);
        var user = await db.Users.AsNoTracking().FirstAsync(u => u.Id == id);
        return Results.Ok(new { user = ToDto(user, jwt) });
    }

    private static async Task<IResult> Update(HttpContext ctx, AppDb db, Jwt jwt)
    {
        var id = await Api.RequiredUser(ctx, db);
        var body = await Api.ReadRoot(ctx, "user");
        var user = await db.Users.FirstAsync(u => u.Id == id);
        var v = new Validation();
        string? newUsername = null, newEmail = null;

        if (Api.TryGetString(body, "username", out var username, out _))
        {
            if (string.IsNullOrWhiteSpace(username)) v.Add("username", Api.Blank);
            else newUsername = username.Trim();
        }
        if (Api.TryGetString(body, "email", out var email, out _))
        {
            if (string.IsNullOrWhiteSpace(email)) v.Add("email", Api.Blank);
            else newEmail = email.Trim();
        }
        string? newPassword = null;
        if (Api.TryGetString(body, "password", out var password, out _))
        {
            CheckPassword(password, v);
            newPassword = password;
        }
        // bio and image are nullable: null and "" both clear the value.
        if (Api.TryGetString(body, "bio", out var bio, out _))
            user.Bio = string.IsNullOrEmpty(bio) ? null : bio;
        if (Api.TryGetString(body, "image", out var image, out _))
            user.Image = string.IsNullOrEmpty(image) ? null : image;
        v.ThrowIfAny();

        await EnsureUnique(db, newUsername, newEmail, id);
        if (newUsername != null) user.Username = newUsername;
        if (newEmail != null) user.Email = newEmail;
        if (newPassword != null) user.PasswordHash = Hasher.HashPassword(user, newPassword);
        await SaveUser(db, user);
        return Results.Ok(new { user = ToDto(user, jwt) });
    }

    private static async Task<IResult> GetProfile(string username, HttpContext ctx, AppDb db)
    {
        var me = Api.OptionalUser(ctx);
        var profile = await LoadProfile(db, username, me);
        return Results.Ok(new { profile });
    }

    private static async Task<IResult> Follow(string username, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Username == username)
                     ?? throw ApiException.NotFound("profile");
        if (target.Id != me && !await db.Follows.AnyAsync(f => f.FollowerId == me && f.FolloweeId == target.Id))
        {
            db.Follows.Add(new Follow { FollowerId = me, FolloweeId = target.Id });
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException e) when (Api.IsUniqueViolation(e)) { /* already followed concurrently */ }
        }
        return Results.Ok(new { profile = new ProfileDto(target.Username, target.Bio, target.Image, target.Id != me) });
    }

    private static async Task<IResult> Unfollow(string username, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Username == username)
                     ?? throw ApiException.NotFound("profile");
        await db.Follows.Where(f => f.FollowerId == me && f.FolloweeId == target.Id).ExecuteDeleteAsync();
        return Results.Ok(new { profile = new ProfileDto(target.Username, target.Bio, target.Image, false) });
    }

    private static async Task<ProfileDto> LoadProfile(AppDb db, string username, int? me)
    {
        return await db.Users.AsNoTracking()
                   .Where(u => u.Username == username)
                   .Select(u => new ProfileDto(u.Username, u.Bio, u.Image,
                       me != null && db.Follows.Any(f => f.FollowerId == me && f.FolloweeId == u.Id)))
                   .FirstOrDefaultAsync()
               ?? throw ApiException.NotFound("profile");
    }
}
