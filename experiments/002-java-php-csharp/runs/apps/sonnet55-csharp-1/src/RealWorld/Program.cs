using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;
using RealWorld;

var builder = WebApplication.CreateBuilder(args);

var port = Environment.GetEnvironmentVariable("PORT") ?? "8000";
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

var dbPath = Environment.GetEnvironmentVariable("DB_PATH") ?? "realworld.db";
var jwtSecret = Environment.GetEnvironmentVariable("JWT_SECRET") ?? "realworld-dev-secret-change-me-in-production-0123456789";

builder.Services.AddSingleton<SqlitePragmaInterceptor>();
builder.Services.AddDbContextPool<AppDb>((sp, o) =>
    o.UseSqlite($"Data Source={dbPath};Default Timeout=5")
     .AddInterceptors(sp.GetRequiredService<SqlitePragmaInterceptor>()));
builder.Services.AddSingleton(new JwtService(jwtSecret));
builder.Logging.SetMinimumLevel(LogLevel.Warning);

var app = builder.Build();

using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDb>();
    db.Database.EnsureCreated();
}

app.Use(async (ctx, next) =>
{
    try { await next(); }
    catch (ApiException e)
    {
        ctx.Response.StatusCode = e.Status;
        await ctx.Response.WriteAsJsonAsync(new { errors = e.Errors });
    }
    catch (JsonException)
    {
        ctx.Response.StatusCode = 422;
        await ctx.Response.WriteAsJsonAsync(new { errors = new { body = new[] { "is invalid" } } });
    }
});

var api = app.MapGroup("/api");

// ---------- Users ----------

api.MapPost("/users", async (HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var u = (await Body(ctx)).Obj("user");
    var username = u.Str("username")?.Trim();
    var email = u.Str("email")?.Trim();
    var password = u.Str("password");
    var errors = new Errors();
    if (string.IsNullOrEmpty(username)) errors.Add("username", "can't be blank");
    if (string.IsNullOrEmpty(email)) errors.Add("email", "can't be blank");
    if (string.IsNullOrEmpty(password)) errors.Add("password", "can't be blank");
    else if (password.Length < 8) errors.Add("password", "is too short (minimum is 8 characters)");
    errors.ThrowIfAny(422);

    if (await db.Users.AnyAsync(x => x.Username == username)) errors.Add("username", "has already been taken");
    if (await db.Users.AnyAsync(x => x.Email == email)) errors.Add("email", "has already been taken");
    errors.ThrowIfAny(409);

    var user = new User { Username = username!, Email = email!, PasswordHash = Passwords.Hash(password!) };
    db.Users.Add(user);
    try { await db.SaveChangesAsync(); }
    catch (DbUpdateException) { throw new ApiException(409, "username", "has already been taken"); }
    return Results.Json(new { user = UserDto(user, jwt) }, statusCode: 201);
});

api.MapPost("/users/login", async (HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var u = (await Body(ctx)).Obj("user");
    var email = u.Str("email");
    var password = u.Str("password");
    var errors = new Errors();
    if (string.IsNullOrEmpty(email)) errors.Add("email", "can't be blank");
    if (string.IsNullOrEmpty(password)) errors.Add("password", "can't be blank");
    errors.ThrowIfAny(422);

    var user = await db.Users.FirstOrDefaultAsync(x => x.Email == email);
    if (user == null || !Passwords.Verify(password!, user.PasswordHash))
        throw new ApiException(401, "credentials", "invalid");
    return Results.Json(new { user = UserDto(user, jwt) });
});

api.MapGet("/user", async (HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var user = await RequireUser(ctx, db, jwt);
    return Results.Json(new { user = UserDto(user, jwt) });
});

api.MapPut("/user", async (HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var user = await RequireUser(ctx, db, jwt);
    var u = (await Body(ctx)).Obj("user");
    var errors = new Errors();

    string? newUsername = null, newEmail = null, newPassword = null;
    if (u.Has("username")) { newUsername = u.Str("username")?.Trim(); if (string.IsNullOrEmpty(newUsername)) errors.Add("username", "can't be blank"); }
    if (u.Has("email")) { newEmail = u.Str("email")?.Trim(); if (string.IsNullOrEmpty(newEmail)) errors.Add("email", "can't be blank"); }
    if (u.Has("password"))
    {
        newPassword = u.Str("password");
        if (string.IsNullOrEmpty(newPassword)) errors.Add("password", "can't be blank");
        else if (newPassword.Length < 8) errors.Add("password", "is too short (minimum is 8 characters)");
    }
    errors.ThrowIfAny(422);

    if (newUsername != null && newUsername != user.Username && await db.Users.AnyAsync(x => x.Username == newUsername))
        errors.Add("username", "has already been taken");
    if (newEmail != null && newEmail != user.Email && await db.Users.AnyAsync(x => x.Email == newEmail))
        errors.Add("email", "has already been taken");
    errors.ThrowIfAny(409);

    if (newUsername != null) user.Username = newUsername;
    if (newEmail != null) user.Email = newEmail;
    if (newPassword != null) user.PasswordHash = Passwords.Hash(newPassword);
    if (u.Has("bio")) user.Bio = NullIfEmpty(u.Str("bio"));
    if (u.Has("image")) user.Image = NullIfEmpty(u.Str("image"));
    await db.SaveChangesAsync();
    return Results.Json(new { user = UserDto(user, jwt) });
});

// ---------- Profiles ----------

api.MapGet("/profiles/{username}", async (string username, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await OptionalUser(ctx, db, jwt);
    var target = await FindProfile(db, username);
    return Results.Json(new { profile = await ProfileDto(db, target, viewer) });
});

api.MapPost("/profiles/{username}/follow", async (string username, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var target = await FindProfile(db, username);
    if (!await db.Follows.AnyAsync(f => f.FollowerId == viewer.Id && f.FolloweeId == target.Id))
    {
        db.Follows.Add(new Follow { FollowerId = viewer.Id, FolloweeId = target.Id });
        try { await db.SaveChangesAsync(); } catch (DbUpdateException) { }
    }
    return Results.Json(new { profile = ProfileOf(target, true) });
});

api.MapDelete("/profiles/{username}/follow", async (string username, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var target = await FindProfile(db, username);
    await db.Follows.Where(f => f.FollowerId == viewer.Id && f.FolloweeId == target.Id).ExecuteDeleteAsync();
    return Results.Json(new { profile = ProfileOf(target, false) });
});

// ---------- Articles ----------

api.MapGet("/articles", async (HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await OptionalUser(ctx, db, jwt);
    var q = ctx.Request.Query;
    IQueryable<Article> query = db.Articles;
    if (q["tag"].ToString() is { Length: > 0 } tag)
        query = query.Where(a => a.Tags.Any(t => t.Tag == tag));
    if (q["author"].ToString() is { Length: > 0 } author)
        query = query.Where(a => a.Author.Username == author);
    if (q["favorited"].ToString() is { Length: > 0 } fav)
        query = query.Where(a => db.Favorites.Any(f => f.ArticleId == a.Id && db.Users.Any(u => u.Id == f.UserId && u.Username == fav)));
    return await ArticleList(db, query, viewer, q);
});

api.MapGet("/articles/feed", async (HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var query = db.Articles.Where(a => db.Follows.Any(f => f.FollowerId == viewer.Id && f.FolloweeId == a.AuthorId));
    return await ArticleList(db, query, viewer, ctx.Request.Query);
});

api.MapGet("/articles/{slug}", async (string slug, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await OptionalUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    return Results.Json(new { article = (await ArticleDtos(db, [article], viewer))[0] });
});

api.MapPost("/articles", async (HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var a = (await Body(ctx)).Obj("article");
    var title = a.Str("title")?.Trim();
    var description = a.Str("description")?.Trim();
    var body = a.Str("body")?.Trim();
    var errors = new Errors();
    if (string.IsNullOrEmpty(title)) errors.Add("title", "can't be blank");
    if (string.IsNullOrEmpty(description)) errors.Add("description", "can't be blank");
    if (string.IsNullOrEmpty(body)) errors.Add("body", "can't be blank");
    errors.ThrowIfAny(422);

    var now = DateTime.UtcNow;
    var article = new Article
    {
        Slug = Slugify(title!) + "-" + Guid.NewGuid().ToString("N")[..8],
        Title = title!, Description = description!, Body = body!,
        AuthorId = viewer.Id, Author = viewer, CreatedAt = now, UpdatedAt = now,
    };
    foreach (var t in a.StrList("tagList") ?? []) article.Tags.Add(new ArticleTag { Tag = t });
    db.Articles.Add(article);
    await db.SaveChangesAsync();
    return Results.Json(new { article = (await ArticleDtos(db, [article], viewer))[0] }, statusCode: 201);
});

api.MapPut("/articles/{slug}", async (string slug, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    if (article.AuthorId != viewer.Id) throw new ApiException(403, "article", "forbidden");
    var a = (await Body(ctx)).Obj("article");
    var errors = new Errors();
    string? title = null, description = null, body = null;
    if (a.Has("title")) { title = a.Str("title")?.Trim(); if (string.IsNullOrEmpty(title)) errors.Add("title", "can't be blank"); }
    if (a.Has("description")) { description = a.Str("description")?.Trim(); if (string.IsNullOrEmpty(description)) errors.Add("description", "can't be blank"); }
    if (a.Has("body")) { body = a.Str("body")?.Trim(); if (string.IsNullOrEmpty(body)) errors.Add("body", "can't be blank"); }
    List<string>? tags = null;
    if (a.Has("tagList"))
    {
        tags = a.StrList("tagList");
        if (tags == null) errors.Add("tagList", "can't be blank");
    }
    errors.ThrowIfAny(422);

    if (title != null) article.Title = title;
    if (description != null) article.Description = description;
    if (body != null) article.Body = body;
    if (tags != null)
    {
        article.Tags.Clear();
        foreach (var t in tags) article.Tags.Add(new ArticleTag { Tag = t });
    }
    article.UpdatedAt = DateTime.UtcNow;
    await db.SaveChangesAsync();
    return Results.Json(new { article = (await ArticleDtos(db, [article], viewer))[0] });
});

api.MapDelete("/articles/{slug}", async (string slug, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    if (article.AuthorId != viewer.Id) throw new ApiException(403, "article", "forbidden");
    db.Articles.Remove(article);
    await db.SaveChangesAsync();
    return Results.NoContent();
});

api.MapPost("/articles/{slug}/favorite", async (string slug, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    if (!await db.Favorites.AnyAsync(f => f.UserId == viewer.Id && f.ArticleId == article.Id))
    {
        db.Favorites.Add(new Favorite { UserId = viewer.Id, ArticleId = article.Id });
        try { await db.SaveChangesAsync(); } catch (DbUpdateException) { }
    }
    return Results.Json(new { article = (await ArticleDtos(db, [article], viewer))[0] });
});

api.MapDelete("/articles/{slug}/favorite", async (string slug, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    await db.Favorites.Where(f => f.UserId == viewer.Id && f.ArticleId == article.Id).ExecuteDeleteAsync();
    return Results.Json(new { article = (await ArticleDtos(db, [article], viewer))[0] });
});

// ---------- Comments ----------

api.MapGet("/articles/{slug}/comments", async (string slug, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await OptionalUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    var comments = await db.Comments.Include(c => c.Author).Where(c => c.ArticleId == article.Id).OrderBy(c => c.Id).ToListAsync();
    var list = new List<object>();
    foreach (var c in comments) list.Add(await CommentDto(db, c, viewer));
    return Results.Json(new { comments = list });
});

api.MapPost("/articles/{slug}/comments", async (string slug, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    var c = (await Body(ctx)).Obj("comment");
    var text = c.Str("body")?.Trim();
    if (string.IsNullOrEmpty(text)) throw new ApiException(422, "body", "can't be blank");
    var now = DateTime.UtcNow;
    var comment = new Comment { ArticleId = article.Id, AuthorId = viewer.Id, Author = viewer, Body = text, CreatedAt = now, UpdatedAt = now };
    db.Comments.Add(comment);
    await db.SaveChangesAsync();
    return Results.Json(new { comment = await CommentDto(db, comment, viewer) }, statusCode: 201);
});

api.MapDelete("/articles/{slug}/comments/{id}", async (string slug, string id, HttpContext ctx, AppDb db, JwtService jwt) =>
{
    var viewer = await RequireUser(ctx, db, jwt);
    var article = await FindArticle(db, slug);
    if (!int.TryParse(id, out var commentId)) throw new ApiException(404, "comment", "not found");
    var comment = await db.Comments.FirstOrDefaultAsync(c => c.Id == commentId && c.ArticleId == article.Id)
        ?? throw new ApiException(404, "comment", "not found");
    if (comment.AuthorId != viewer.Id) throw new ApiException(403, "comment", "forbidden");
    db.Comments.Remove(comment);
    await db.SaveChangesAsync();
    return Results.NoContent();
});

// ---------- Tags ----------

api.MapGet("/tags", async (AppDb db) =>
{
    var tags = await db.ArticleTags.Select(t => t.Tag).Distinct().OrderBy(t => t).ToListAsync();
    return Results.Json(new { tags });
});

app.Run();

// ---------- Helpers ----------

static string? NullIfEmpty(string? s) => string.IsNullOrEmpty(s) ? null : s;

static string Slugify(string title)
{
    var s = Regex.Replace(title.ToLowerInvariant(), @"[^\p{L}\p{N}]+", "-").Trim('-');
    return s.Length == 0 ? "article" : s;
}

static string Iso(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

static async Task<JsonElement> Body(HttpContext ctx)
{
    try
    {
        using var doc = await JsonDocument.ParseAsync(ctx.Request.Body);
        return doc.RootElement.Clone();
    }
    catch (JsonException) { throw new ApiException(422, "body", "is invalid"); }
}

static object UserDto(User u, JwtService jwt) => new { email = u.Email, token = jwt.Create(u.Id), username = u.Username, bio = u.Bio, image = u.Image };

static object ProfileOf(User u, bool following) => new { username = u.Username, bio = u.Bio, image = u.Image, following };

static async Task<object> ProfileDto(AppDb db, User target, User? viewer)
{
    var following = viewer != null && await db.Follows.AnyAsync(f => f.FollowerId == viewer.Id && f.FolloweeId == target.Id);
    return ProfileOf(target, following);
}

static async Task<object> CommentDto(AppDb db, Comment c, User? viewer) => new
{
    id = c.Id, createdAt = Iso(c.CreatedAt), updatedAt = Iso(c.UpdatedAt), body = c.Body,
    author = await ProfileDto(db, c.Author, viewer),
};

static string? ExtractToken(HttpContext ctx)
{
    var h = ctx.Request.Headers.Authorization.ToString();
    if (string.IsNullOrWhiteSpace(h)) return null;
    var parts = h.Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
    return parts.Length == 2 && (parts[0] == "Token" || parts[0] == "Bearer") ? parts[1] : (parts.Length == 1 ? parts[0] : null);
}

static async Task<User> RequireUser(HttpContext ctx, AppDb db, JwtService jwt)
{
    var token = ExtractToken(ctx) ?? throw new ApiException(401, "token", "is missing");
    var id = jwt.Validate(token) ?? throw new ApiException(401, "token", "is invalid");
    return await db.Users.FindAsync(id) ?? throw new ApiException(401, "token", "is invalid");
}

static async Task<User?> OptionalUser(HttpContext ctx, AppDb db, JwtService jwt)
{
    var token = ExtractToken(ctx);
    if (token == null) return null;
    var id = jwt.Validate(token);
    return id == null ? null : await db.Users.FindAsync(id.Value);
}

static async Task<User> FindProfile(AppDb db, string username) =>
    await db.Users.FirstOrDefaultAsync(u => u.Username == username) ?? throw new ApiException(404, "profile", "not found");

static async Task<Article> FindArticle(AppDb db, string slug) =>
    await db.Articles.Include(a => a.Author).Include(a => a.Tags).FirstOrDefaultAsync(a => a.Slug == slug)
        ?? throw new ApiException(404, "article", "not found");

static async Task<IResult> ArticleList(AppDb db, IQueryable<Article> query, User? viewer, IQueryCollection q)
{
    var limit = int.TryParse(q["limit"], out var l) && l >= 0 ? l : 20;
    var offset = int.TryParse(q["offset"], out var o) && o >= 0 ? o : 0;
    var count = await query.CountAsync();
    var page = await query.Include(a => a.Author).Include(a => a.Tags)
        .OrderByDescending(a => a.CreatedAt).ThenByDescending(a => a.Id)
        .Skip(offset).Take(limit).ToListAsync();
    var dtos = await ArticleDtos(db, page, viewer);
    var items = dtos.Select(d => { d.Remove("body"); return d; }).ToList();
    return Results.Json(new { articles = items, articlesCount = count });
}

static async Task<List<Dictionary<string, object?>>> ArticleDtos(AppDb db, List<Article> articles, User? viewer)
{
    var ids = articles.Select(a => a.Id).ToList();
    var counts = await db.Favorites.Where(f => ids.Contains(f.ArticleId))
        .GroupBy(f => f.ArticleId).Select(g => new { g.Key, N = g.Count() }).ToDictionaryAsync(x => x.Key, x => x.N);
    var favorited = viewer == null ? new HashSet<int>()
        : (await db.Favorites.Where(f => f.UserId == viewer.Id && ids.Contains(f.ArticleId)).Select(f => f.ArticleId).ToListAsync()).ToHashSet();
    var followed = viewer == null ? new HashSet<int>()
        : (await db.Follows.Where(f => f.FollowerId == viewer.Id).Select(f => f.FolloweeId).ToListAsync()).ToHashSet();
    return articles.Select(a => new Dictionary<string, object?>
    {
        ["slug"] = a.Slug, ["title"] = a.Title, ["description"] = a.Description, ["body"] = a.Body,
        ["tagList"] = a.Tags.Select(t => t.Tag).OrderBy(t => t, StringComparer.Ordinal).ToList(),
        ["createdAt"] = Iso(a.CreatedAt), ["updatedAt"] = Iso(a.UpdatedAt),
        ["favorited"] = favorited.Contains(a.Id),
        ["favoritesCount"] = counts.GetValueOrDefault(a.Id),
        ["author"] = ProfileOf(a.Author, followed.Contains(a.AuthorId)),
    }).ToList();
}

namespace RealWorld
{
    public class ApiException(int status, Dictionary<string, List<string>> errors) : Exception
    {
        public int Status { get; } = status;
        public Dictionary<string, List<string>> Errors { get; } = errors;
        public ApiException(int status, string field, string message)
            : this(status, new Dictionary<string, List<string>> { [field] = [message] }) { }
    }

    public class Errors
    {
        private readonly Dictionary<string, List<string>> _d = new();
        public void Add(string field, string msg)
        {
            if (!_d.TryGetValue(field, out var l)) _d[field] = l = new();
            l.Add(msg);
        }
        public void ThrowIfAny(int status) { if (_d.Count > 0) throw new ApiException(status, _d); }
    }

    public static class JsonExt
    {
        public static JsonElement Obj(this JsonElement e, string name)
        {
            if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty(name, out var v) || v.ValueKind != JsonValueKind.Object)
                throw new ApiException(422, name, "can't be blank");
            return v;
        }
        public static bool Has(this JsonElement e, string name) => e.TryGetProperty(name, out _);
        public static string? Str(this JsonElement e, string name) =>
            e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
        /// <summary>Returns null when the property is absent, null, or not an array of strings.</summary>
        public static List<string>? StrList(this JsonElement e, string name)
        {
            if (!e.TryGetProperty(name, out var v) || v.ValueKind != JsonValueKind.Array) return null;
            return v.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String)
                .Select(x => x.GetString()!).Where(s => s.Length > 0).Distinct().ToList();
        }
    }
}
