using Microsoft.EntityFrameworkCore;

namespace Conduit;

public static class UserEndpoints
{
    private const int MinPasswordLength = 8;

    public static void Map(WebApplication app)
    {
        app.MapPost("/api/users", Register);
        app.MapPost("/api/users/login", Login);
        app.MapGet("/api/user", Current);
        app.MapPut("/api/user", Update);
        app.MapGet("/api/profiles/{username}", GetProfile);
        app.MapPost("/api/profiles/{username}/follow", Follow);
        app.MapDelete("/api/profiles/{username}/follow", Unfollow);
    }

    private static object UserJson(User u, string token) => new
    {
        user = new { username = u.Username, email = u.Email, bio = u.Bio, image = u.Image, token }
    };

    public static object ProfileJson(User u, bool following) =>
        new { username = u.Username, bio = u.Bio, image = u.Image, following };

    private static async Task<IResult> Register(HttpContext ctx, AppDb db, JwtService jwt)
    {
        var body = await Body.Read(ctx, "user");
        if (body is null) return Body.Invalid("user");

        var errors = new Dictionary<string, List<string>>();
        foreach (var name in new[] { "username", "email", "password" })
            if (!body[name].Present || body[name].IsBlankOrInvalid)
                errors[name] = ["can't be blank"];
        if (errors.Count == 0 && body["password"].Str!.Length < MinPasswordLength)
            errors["password"] = [$"is too short (minimum is {MinPasswordLength} characters)"];
        if (errors.Count > 0) return Errors.Of(422, errors);

        var username = body["username"].Str!.Trim();
        var email = body["email"].Str!.Trim();
        var conflict = await Conflicts(db, username, email, null);
        if (conflict is not null) return conflict;

        var user = new User { Username = username, Email = email, PasswordHash = Passwords.Hash(body["password"].Str!) };
        db.Users.Add(user);
        try
        {
            await db.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            return await Conflicts(db, username, email, null) ?? Errors.Of(409, "username", "has already been taken");
        }
        return Results.Json(UserJson(user, jwt.Issue(user.Id)), statusCode: 201);
    }

    private static async Task<IResult?> Conflicts(AppDb db, string? username, string? email, int? exceptId)
    {
        var errors = new Dictionary<string, List<string>>();
        if (username is not null && await db.Users.AnyAsync(u => u.Username == username && u.Id != exceptId))
            errors["username"] = ["has already been taken"];
        if (email is not null && await db.Users.AnyAsync(u => u.Email == email && u.Id != exceptId))
            errors["email"] = ["has already been taken"];
        return errors.Count > 0 ? Errors.Of(409, errors) : null;
    }

    private static async Task<IResult> Login(HttpContext ctx, AppDb db, JwtService jwt)
    {
        var body = await Body.Read(ctx, "user");
        if (body is null) return Body.Invalid("user");

        var errors = new Dictionary<string, List<string>>();
        foreach (var name in new[] { "email", "password" })
            if (!body[name].Present || body[name].IsBlankOrInvalid)
                errors[name] = ["can't be blank"];
        if (errors.Count > 0) return Errors.Of(422, errors);

        var email = body["email"].Str!.Trim();
        var user = await db.Users.FirstOrDefaultAsync(u => u.Email == email);
        if (user is null || !Passwords.Verify(body["password"].Str!, user.PasswordHash))
            return Errors.Of(401, "credentials", "invalid");
        return Results.Json(UserJson(user, jwt.Issue(user.Id)));
    }

    private static async Task<IResult> Current(HttpContext ctx, AppDb db, JwtService jwt)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var user = await db.Users.FindAsync(uid);
        if (user is null) return Errors.Unauthorized(AuthState.Invalid);
        return Results.Json(UserJson(user, jwt.Issue(user.Id)));
    }

    private static async Task<IResult> Update(HttpContext ctx, AppDb db, JwtService jwt)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var user = await db.Users.FindAsync(uid);
        if (user is null) return Errors.Unauthorized(AuthState.Invalid);

        var body = await Body.Read(ctx, "user");
        if (body is null) return Body.Invalid("user");

        // username, email and password may be omitted but never set to null/empty.
        var errors = new Dictionary<string, List<string>>();
        foreach (var name in new[] { "username", "email", "password" })
            if (body[name].IsBlankOrInvalid)
                errors[name] = ["can't be blank"];
        var password = body["password"].Str;
        if (!errors.ContainsKey("password") && password is not null && password.Length < MinPasswordLength)
            errors["password"] = [$"is too short (minimum is {MinPasswordLength} characters)"];
        // bio and image are nullable; any non-string, non-null value is rejected.
        foreach (var name in new[] { "bio", "image" })
            if (body[name].Present && !body[name].IsNull && !body[name].IsString)
                errors[name] = ["is invalid"];
        if (errors.Count > 0) return Errors.Of(422, errors);

        var username = body["username"].Str?.Trim();
        var email = body["email"].Str?.Trim();
        var conflict = await Conflicts(db, username, email, user.Id);
        if (conflict is not null) return conflict;

        if (username is not null) user.Username = username;
        if (email is not null) user.Email = email;
        if (password is not null) user.PasswordHash = Passwords.Hash(password);
        if (body["bio"].Present) user.Bio = NullIfEmpty(body["bio"].Str);
        if (body["image"].Present) user.Image = NullIfEmpty(body["image"].Str);

        try
        {
            await db.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            return Errors.Of(409, "username", "has already been taken");
        }
        return Results.Json(UserJson(user, jwt.Issue(user.Id)));
    }

    private static string? NullIfEmpty(string? s) => string.IsNullOrEmpty(s) ? null : s;

    private static async Task<IResult> GetProfile(HttpContext ctx, AppDb db, string username)
    {
        var (state, uid) = ctx.Authenticate();
        if (state == AuthState.Invalid) return Errors.Unauthorized(state);
        var user = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Username == username);
        if (user is null) return Errors.NotFound("profile");
        var following = uid is not null && await db.Follows.AnyAsync(f => f.FollowerId == uid && f.FolloweeId == user.Id);
        return Results.Json(new { profile = ProfileJson(user, following) });
    }

    private static Task<IResult> Follow(HttpContext ctx, AppDb db, string username) => SetFollow(ctx, db, username, true);
    private static Task<IResult> Unfollow(HttpContext ctx, AppDb db, string username) => SetFollow(ctx, db, username, false);

    private static async Task<IResult> SetFollow(HttpContext ctx, AppDb db, string username, bool follow)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Username == username);
        if (target is null) return Errors.NotFound("profile");

        var existing = await db.Follows.FindAsync(uid!.Value, target.Id);
        if (follow && existing is null && target.Id != uid)
        {
            db.Follows.Add(new Follow { FollowerId = uid.Value, FolloweeId = target.Id });
            try { await db.SaveChangesAsync(); } catch (DbUpdateException) { /* concurrent follow: already exists */ }
        }
        else if (!follow && existing is not null)
        {
            db.Follows.Remove(existing);
            await db.SaveChangesAsync();
        }
        return Results.Json(new { profile = ProfileJson(target, follow && target.Id != uid) });
    }
}
