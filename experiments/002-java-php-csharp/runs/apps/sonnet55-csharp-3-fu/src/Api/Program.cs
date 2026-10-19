using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using RealWorld.Api;

var builder = WebApplication.CreateBuilder(args);

var port = Environment.GetEnvironmentVariable("PORT") ?? "8000";
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
builder.Logging.SetMinimumLevel(LogLevel.Warning);

var dbPath = Environment.GetEnvironmentVariable("DATABASE_PATH") ?? "realworld.db";
// "Default Timeout=5" makes Microsoft.Data.Sqlite apply a 5000 ms busy_timeout.
var connectionString = $"Data Source={dbPath};Default Timeout=5;Pooling=True";
builder.Services.AddDbContextPool<AppDb>(o => o.UseSqlite(connectionString), poolSize: 256);
builder.Services.AddSingleton(new Security(
    Environment.GetEnvironmentVariable("JWT_SECRET") ?? "realworld-dev-secret-change-me-in-production-0123456789"));
builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase);

var app = builder.Build();

using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDb>();
    db.Database.ExecuteSqlRaw("PRAGMA journal_mode=WAL;");
    db.Database.EnsureCreated();
}

// ---------- helpers ----------

static IResult Errors(int status, string field, string message) =>
    Results.Json(new { errors = new Dictionary<string, string[]> { [field] = [message] } }, statusCode: status);

static IResult Invalid(Dictionary<string, string[]> errors) =>
    Results.Json(new { errors }, statusCode: 422);

static string Iso(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

static async Task<JsonElement?> ReadObject(HttpRequest req, string key)
{
    try
    {
        using var doc = await JsonDocument.ParseAsync(req.Body);
        if (doc.RootElement.ValueKind == JsonValueKind.Object &&
            doc.RootElement.TryGetProperty(key, out var inner) && inner.ValueKind == JsonValueKind.Object)
            return inner.Clone();
    }
    catch (JsonException) { }
    return null;
}

// Returns (present, value) for a string property; non-string values count as null.
static (bool present, string? value) Str(JsonElement obj, string name)
{
    if (!obj.TryGetProperty(name, out var p)) return (false, null);
    return (true, p.ValueKind == JsonValueKind.String ? p.GetString() : null);
}

static bool Blank(string? s) => string.IsNullOrWhiteSpace(s);

// Authentication: 'Token <jwt>' (or 'Bearer <jwt>'). Result is either a user id or an error response.
static (int? userId, IResult? error) Auth(HttpRequest req, Security sec, bool required)
{
    var header = req.Headers.Authorization.ToString();
    if (string.IsNullOrWhiteSpace(header))
        return required ? (null, Errors(401, "token", "is missing")) : (null, null);
    var idx = header.IndexOf(' ');
    var token = idx > 0 ? header[(idx + 1)..].Trim() : header.Trim();
    var id = sec.ValidateToken(token);
    if (id is null)
        return required ? (null, Errors(401, "token", "is invalid")) : (null, null);
    return (id, null);
}

static object UserJson(User u, string token) => new
{
    user = new { email = u.Email, username = u.Username, bio = u.Bio, image = u.Image, token }
};

static object ProfileObj(User u, bool following) => new { username = u.Username, bio = u.Bio, image = u.Image, following };

static string Slugify(string title)
{
    var s = Regex.Replace(title.ToLowerInvariant(), @"[^a-z0-9]+", "-").Trim('-');
    if (s.Length > 80) s = s[..80].Trim('-');
    if (s.Length == 0) s = "article";
    return $"{s}-{Guid.NewGuid().ToString("N")[..8]}";
}

static async Task<object> ArticleObj(AppDb db, Article a, int? viewerId, bool withBody)
{
    var count = await db.Favorites.CountAsync(f => f.ArticleId == a.Id);
    var fav = viewerId is int v && await db.Favorites.AnyAsync(f => f.ArticleId == a.Id && f.UserId == v);
    var following = viewerId is int w && await db.Follows.AnyAsync(f => f.FollowerId == w && f.FolloweeId == a.AuthorId);
    return BuildArticle(a, count, fav, following, withBody);
}

static object BuildArticle(Article a, int favCount, bool favorited, bool following, bool withBody)
{
    var tags = a.Tags.OrderBy(t => t.Id).Select(t => t.Name).ToArray();
    var author = ProfileObj(a.Author, following);
    var result = new Dictionary<string, object?>
    {
        ["slug"] = a.Slug, ["title"] = a.Title, ["description"] = a.Description,
    };
    if (withBody) result["body"] = a.Body;
    result["tagList"] = tags;
    result["createdAt"] = Iso(a.CreatedAt);
    result["updatedAt"] = Iso(a.UpdatedAt);
    result["favorited"] = favorited;
    result["favoritesCount"] = favCount;
    result["author"] = author;
    return result;
}

// Builds list responses with a fixed number of queries (no per-article lookups).
static async Task<IResult> ArticleList(AppDb db, IQueryable<Article> query, int? viewerId, HttpRequest req)
{
    int limit = int.TryParse(req.Query["limit"], out var l) && l >= 0 ? Math.Min(l, 100) : 20;
    int offset = int.TryParse(req.Query["offset"], out var o) && o >= 0 ? o : 0;
    var total = await query.CountAsync();
    var items = await query.Include(a => a.Author).Include(a => a.Tags)
        .OrderByDescending(a => a.Id).Skip(offset).Take(limit).AsSplitQuery().AsNoTracking().ToListAsync();
    var ids = items.Select(a => a.Id).ToList();
    var authorIds = items.Select(a => a.AuthorId).Distinct().ToList();
    var counts = await db.Favorites.Where(f => ids.Contains(f.ArticleId))
        .GroupBy(f => f.ArticleId).Select(g => new { g.Key, N = g.Count() }).ToDictionaryAsync(x => x.Key, x => x.N);
    var favSet = viewerId is int v
        ? (await db.Favorites.Where(f => f.UserId == v && ids.Contains(f.ArticleId)).Select(f => f.ArticleId).ToListAsync()).ToHashSet()
        : new HashSet<int>();
    var followSet = viewerId is int w
        ? (await db.Follows.Where(f => f.FollowerId == w && authorIds.Contains(f.FolloweeId)).Select(f => f.FolloweeId).ToListAsync()).ToHashSet()
        : new HashSet<int>();
    var list = items.Select(a => BuildArticle(a, counts.GetValueOrDefault(a.Id), favSet.Contains(a.Id),
        followSet.Contains(a.AuthorId), false)).ToList();
    return Results.Json(new { articles = list, articlesCount = total });
}

// ---------- users ----------

app.MapPost("/api/users", async (HttpRequest req, AppDb db, Security sec) =>
{
    var u = await ReadObject(req, "user");
    if (u is null) return Invalid(new() { ["user"] = ["can't be blank"] });
    var (_, username) = Str(u.Value, "username");
    var (_, email) = Str(u.Value, "email");
    var (_, password) = Str(u.Value, "password");
    var errs = new Dictionary<string, string[]>();
    if (Blank(username)) errs["username"] = ["can't be blank"];
    if (Blank(email)) errs["email"] = ["can't be blank"];
    if (Blank(password)) errs["password"] = ["can't be blank"];
    else if (password!.Length < 8) errs["password"] = ["is too short (minimum is 8 characters)"];
    if (errs.Count > 0) return Invalid(errs);

    if (await db.Users.AnyAsync(x => x.Username == username)) return Errors(409, "username", "has already been taken");
    if (await db.Users.AnyAsync(x => x.Email == email)) return Errors(409, "email", "has already been taken");

    var user = new User { Username = username!, Email = email!, PasswordHash = Security.HashPassword(password!) };
    db.Users.Add(user);
    try { await db.SaveChangesAsync(); }
    catch (DbUpdateException)
    {
        return Errors(409, "username", "has already been taken");
    }
    return Results.Json(UserJson(user, sec.CreateToken(user)), statusCode: 201);
});

app.MapPost("/api/users/login", async (HttpRequest req, AppDb db, Security sec) =>
{
    var u = await ReadObject(req, "user");
    if (u is null) return Invalid(new() { ["user"] = ["can't be blank"] });
    var (_, email) = Str(u.Value, "email");
    var (_, password) = Str(u.Value, "password");
    var errs = new Dictionary<string, string[]>();
    if (Blank(email)) errs["email"] = ["can't be blank"];
    if (Blank(password)) errs["password"] = ["can't be blank"];
    if (errs.Count > 0) return Invalid(errs);

    var user = await db.Users.AsNoTracking().FirstOrDefaultAsync(x => x.Email == email);
    if (user is null || !Security.VerifyPassword(password!, user.PasswordHash))
        return Errors(401, "credentials", "invalid");
    return Results.Json(UserJson(user, sec.CreateToken(user)));
});

app.MapGet("/api/user", async (HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var user = await db.Users.AsNoTracking().FirstOrDefaultAsync(x => x.Id == uid);
    if (user is null) return Errors(401, "token", "is invalid");
    return Results.Json(UserJson(user, sec.CreateToken(user)));
});

app.MapPut("/api/user", async (HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var user = await db.Users.FirstOrDefaultAsync(x => x.Id == uid);
    if (user is null) return Errors(401, "token", "is invalid");
    var u = await ReadObject(req, "user");
    if (u is null) return Invalid(new() { ["user"] = ["can't be blank"] });

    var errs = new Dictionary<string, string[]>();
    var (hasName, username) = Str(u.Value, "username");
    var (hasEmail, email) = Str(u.Value, "email");
    var (hasPw, password) = Str(u.Value, "password");
    var (hasBio, bio) = Str(u.Value, "bio");
    var (hasImage, image) = Str(u.Value, "image");
    if (hasName && Blank(username)) errs["username"] = ["can't be blank"];
    if (hasEmail && Blank(email)) errs["email"] = ["can't be blank"];
    if (hasPw && Blank(password)) errs["password"] = ["can't be blank"];
    else if (hasPw && password!.Length < 8) errs["password"] = ["is too short (minimum is 8 characters)"];
    if (errs.Count > 0) return Invalid(errs);

    if (hasName && username != user.Username && await db.Users.AnyAsync(x => x.Username == username))
        return Errors(409, "username", "has already been taken");
    if (hasEmail && email != user.Email && await db.Users.AnyAsync(x => x.Email == email))
        return Errors(409, "email", "has already been taken");

    if (hasName) user.Username = username!;
    if (hasEmail) user.Email = email!;
    if (hasPw) user.PasswordHash = Security.HashPassword(password!);
    if (hasBio) user.Bio = Blank(bio) ? null : bio;
    if (hasImage) user.Image = Blank(image) ? null : image;
    try { await db.SaveChangesAsync(); }
    catch (DbUpdateException) { return Errors(409, "username", "has already been taken"); }
    return Results.Json(UserJson(user, sec.CreateToken(user)));
});

// ---------- profiles ----------

app.MapGet("/api/profiles/{username}", async (string username, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, _) = Auth(req, sec, false);
    var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(x => x.Username == username);
    if (target is null) return Errors(404, "profile", "not found");
    var following = uid is int v && await db.Follows.AnyAsync(f => f.FollowerId == v && f.FolloweeId == target.Id);
    return Results.Json(new { profile = ProfileObj(target, following) });
});

app.MapPost("/api/profiles/{username}/follow", async (string username, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(x => x.Username == username);
    if (target is null) return Errors(404, "profile", "not found");
    if (!await db.Follows.AnyAsync(f => f.FollowerId == uid && f.FolloweeId == target.Id))
    {
        db.Follows.Add(new Follow { FollowerId = uid!.Value, FolloweeId = target.Id });
        try { await db.SaveChangesAsync(); } catch (DbUpdateException) { }
    }
    return Results.Json(new { profile = ProfileObj(target, true) });
});

app.MapDelete("/api/profiles/{username}/follow", async (string username, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(x => x.Username == username);
    if (target is null) return Errors(404, "profile", "not found");
    await db.Follows.Where(f => f.FollowerId == uid && f.FolloweeId == target.Id).ExecuteDeleteAsync();
    return Results.Json(new { profile = ProfileObj(target, false) });
});

// ---------- articles ----------

app.MapGet("/api/articles/feed", async (HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var q = db.Articles.Where(a => db.Follows.Any(f => f.FollowerId == uid && f.FolloweeId == a.AuthorId));
    return await ArticleList(db, q, uid, req);
});

app.MapGet("/api/articles", async (HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, _) = Auth(req, sec, false);
    IQueryable<Article> q = db.Articles;
    string? tag = req.Query["tag"], author = req.Query["author"], favorited = req.Query["favorited"];
    if (!string.IsNullOrEmpty(tag)) q = q.Where(a => a.Tags.Any(t => t.Name == tag));
    if (!string.IsNullOrEmpty(author)) q = q.Where(a => a.Author.Username == author);
    if (!string.IsNullOrEmpty(favorited))
        q = q.Where(a => db.Favorites.Any(f => f.ArticleId == a.Id && db.Users.Any(u => u.Id == f.UserId && u.Username == favorited)));
    return await ArticleList(db, q, uid, req);
});

app.MapGet("/api/articles/{slug}", async (string slug, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, _) = Auth(req, sec, false);
    var a = await db.Articles.Include(x => x.Author).Include(x => x.Tags).AsNoTracking().FirstOrDefaultAsync(x => x.Slug == slug);
    if (a is null) return Errors(404, "article", "not found");
    return Results.Json(new { article = await ArticleObj(db, a, uid, true) });
});

app.MapPost("/api/articles", async (HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var j = await ReadObject(req, "article");
    if (j is null) return Invalid(new() { ["article"] = ["can't be blank"] });
    var (_, title) = Str(j.Value, "title");
    var (_, description) = Str(j.Value, "description");
    var (_, body) = Str(j.Value, "body");
    var errs = new Dictionary<string, string[]>();
    if (Blank(title)) errs["title"] = ["can't be blank"];
    if (Blank(description)) errs["description"] = ["can't be blank"];
    if (Blank(body)) errs["body"] = ["can't be blank"];
    List<string>? tags = [];
    if (j.Value.TryGetProperty("tagList", out var tl))
    {
        tags = ParseTags(tl);
        if (tags is null) errs["tagList"] = ["is invalid"];
    }
    if (errs.Count > 0) return Invalid(errs);

    var now = DateTime.UtcNow;
    var article = new Article
    {
        Slug = Slugify(title!), Title = title!, Description = description!, Body = body!,
        CreatedAt = now, UpdatedAt = now, AuthorId = uid!.Value,
        Tags = tags!.Select(t => new ArticleTag { Name = t }).ToList()
    };
    db.Articles.Add(article);
    await db.SaveChangesAsync();
    var full = await db.Articles.Include(x => x.Author).Include(x => x.Tags).AsNoTracking().FirstAsync(x => x.Id == article.Id);
    return Results.Json(new { article = await ArticleObj(db, full, uid, true) }, statusCode: 201);
});

app.MapPut("/api/articles/{slug}", async (string slug, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var a = await db.Articles.Include(x => x.Author).Include(x => x.Tags).FirstOrDefaultAsync(x => x.Slug == slug);
    if (a is null) return Errors(404, "article", "not found");
    if (a.AuthorId != uid) return Errors(403, "article", "forbidden");
    var j = await ReadObject(req, "article");
    if (j is null) return Invalid(new() { ["article"] = ["can't be blank"] });

    var errs = new Dictionary<string, string[]>();
    var (hasTitle, title) = Str(j.Value, "title");
    var (hasDesc, description) = Str(j.Value, "description");
    var (hasBody, body) = Str(j.Value, "body");
    if (hasTitle && Blank(title)) errs["title"] = ["can't be blank"];
    if (hasDesc && Blank(description)) errs["description"] = ["can't be blank"];
    if (hasBody && Blank(body)) errs["body"] = ["can't be blank"];
    List<string>? tags = null;
    var hasTags = j.Value.TryGetProperty("tagList", out var tl);
    if (hasTags)
    {
        tags = ParseTags(tl);
        if (tags is null) errs["tagList"] = ["is invalid"];
    }
    if (errs.Count > 0) return Invalid(errs);

    if (hasTitle) a.Title = title!;
    if (hasDesc) a.Description = description!;
    if (hasBody) a.Body = body!;
    if (hasTags)
    {
        db.ArticleTags.RemoveRange(a.Tags);
        a.Tags = tags!.Select(t => new ArticleTag { Name = t, ArticleId = a.Id }).ToList();
    }
    a.UpdatedAt = DateTime.UtcNow;
    await db.SaveChangesAsync();
    return Results.Json(new { article = await ArticleObj(db, a, uid, true) });
});

app.MapDelete("/api/articles/{slug}", async (string slug, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var a = await db.Articles.AsNoTracking().FirstOrDefaultAsync(x => x.Slug == slug);
    if (a is null) return Errors(404, "article", "not found");
    if (a.AuthorId != uid) return Errors(403, "article", "forbidden");
    await db.Comments.Where(c => c.ArticleId == a.Id).ExecuteDeleteAsync();
    await db.Favorites.Where(f => f.ArticleId == a.Id).ExecuteDeleteAsync();
    await db.ArticleTags.Where(t => t.ArticleId == a.Id).ExecuteDeleteAsync();
    await db.Articles.Where(x => x.Id == a.Id).ExecuteDeleteAsync();
    return Results.NoContent();
});

static List<string>? ParseTags(JsonElement e)
{
    if (e.ValueKind != JsonValueKind.Array) return null;
    var list = new List<string>();
    foreach (var item in e.EnumerateArray())
    {
        if (item.ValueKind != JsonValueKind.String) return null;
        var s = item.GetString()!.Trim();
        if (s.Length > 0 && !list.Contains(s)) list.Add(s);
    }
    return list;
}

// ---------- favorites ----------

app.MapPost("/api/articles/{slug}/favorite", async (string slug, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var a = await db.Articles.Include(x => x.Author).Include(x => x.Tags).AsNoTracking().FirstOrDefaultAsync(x => x.Slug == slug);
    if (a is null) return Errors(404, "article", "not found");
    if (!await db.Favorites.AnyAsync(f => f.UserId == uid && f.ArticleId == a.Id))
    {
        db.Favorites.Add(new Favorite { UserId = uid!.Value, ArticleId = a.Id });
        try { await db.SaveChangesAsync(); } catch (DbUpdateException) { }
    }
    return Results.Json(new { article = await ArticleObj(db, a, uid, true) });
});

app.MapDelete("/api/articles/{slug}/favorite", async (string slug, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var a = await db.Articles.Include(x => x.Author).Include(x => x.Tags).AsNoTracking().FirstOrDefaultAsync(x => x.Slug == slug);
    if (a is null) return Errors(404, "article", "not found");
    await db.Favorites.Where(f => f.UserId == uid && f.ArticleId == a.Id).ExecuteDeleteAsync();
    return Results.Json(new { article = await ArticleObj(db, a, uid, true) });
});

// ---------- comments ----------

static object CommentObj(Comment c, User author, bool following) => new
{
    id = c.Id, createdAt = Iso(c.CreatedAt), updatedAt = Iso(c.UpdatedAt), body = c.Body,
    author = ProfileObj(author, following)
};

app.MapGet("/api/articles/{slug}/comments", async (string slug, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, _) = Auth(req, sec, false);
    var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
    if (articleId is null) return Errors(404, "article", "not found");
    var items = await db.Comments.Include(c => c.Author).AsNoTracking()
        .Where(c => c.ArticleId == articleId).OrderByDescending(c => c.Id).ToListAsync();
    var followSet = uid is int v
        ? (await db.Follows.Where(f => f.FollowerId == v).Select(f => f.FolloweeId).ToListAsync()).ToHashSet()
        : new HashSet<int>();
    return Results.Json(new { comments = items.Select(c => CommentObj(c, c.Author, followSet.Contains(c.AuthorId))) });
});

app.MapPost("/api/articles/{slug}/comments", async (string slug, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
    if (articleId is null) return Errors(404, "article", "not found");
    var j = await ReadObject(req, "comment");
    if (j is null) return Invalid(new() { ["comment"] = ["can't be blank"] });
    var (_, body) = Str(j.Value, "body");
    if (Blank(body)) return Invalid(new() { ["body"] = ["can't be blank"] });
    var now = DateTime.UtcNow;
    var c = new Comment { ArticleId = articleId.Value, AuthorId = uid!.Value, Body = body!, CreatedAt = now, UpdatedAt = now };
    db.Comments.Add(c);
    await db.SaveChangesAsync();
    var author = await db.Users.AsNoTracking().FirstAsync(x => x.Id == uid);
    return Results.Json(new { comment = CommentObj(c, author, false) }, statusCode: 201);
});

app.MapPut("/api/articles/{slug}/comments/{id}", async (string slug, string id, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
    if (articleId is null) return Errors(404, "article", "not found");
    if (!int.TryParse(id, out var cid)) return Errors(404, "comment", "not found");
    var c = await db.Comments.Include(x => x.Author).FirstOrDefaultAsync(x => x.Id == cid && x.ArticleId == articleId);
    if (c is null) return Errors(404, "comment", "not found");
    if (c.AuthorId != uid) return Errors(403, "comment", "forbidden");
    var j = await ReadObject(req, "comment");
    if (j is null) return Invalid(new() { ["comment"] = ["can't be blank"] });
    var (_, body) = Str(j.Value, "body");
    if (Blank(body)) return Invalid(new() { ["body"] = ["can't be blank"] });
    c.Body = body!;
    c.UpdatedAt = DateTime.UtcNow;
    await db.SaveChangesAsync();
    return Results.Json(new { comment = CommentObj(c, c.Author, false) });
});

app.MapDelete("/api/articles/{slug}/comments/{id}", async (string slug, string id, HttpRequest req, AppDb db, Security sec) =>
{
    var (uid, err) = Auth(req, sec, true);
    if (err is not null) return err;
    var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
    if (articleId is null) return Errors(404, "article", "not found");
    if (!int.TryParse(id, out var cid)) return Errors(404, "comment", "not found");
    var c = await db.Comments.AsNoTracking().FirstOrDefaultAsync(x => x.Id == cid && x.ArticleId == articleId);
    if (c is null) return Errors(404, "comment", "not found");
    if (c.AuthorId != uid) return Errors(403, "comment", "forbidden");
    await db.Comments.Where(x => x.Id == cid).ExecuteDeleteAsync();
    return Results.NoContent();
});

// ---------- tags ----------

app.MapGet("/api/tags", async (AppDb db) =>
{
    var tags = await db.ArticleTags.AsNoTracking().Select(t => t.Name).Distinct().OrderBy(n => n).ToListAsync();
    return Results.Json(new { tags });
});

app.Run();
