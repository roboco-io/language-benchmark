using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;

namespace RealWorld;

public static class Api
{
    private const string DateFormat = "yyyy-MM-dd'T'HH:mm:ss.fff'Z'";
    private static readonly TimeSpan TokenLifetime = TimeSpan.FromDays(7);

    // ---------- helpers ----------

    public static Task Errors(HttpContext ctx, int status, string field, string message)
        => Results.Json(new { errors = new Dictionary<string, string[]> { [field] = [message] } }, statusCode: status).ExecuteAsync(ctx);

    private static IResult Err(int status, string field, string message)
        => Results.Json(new { errors = new Dictionary<string, string[]> { [field] = [message] } }, statusCode: status);

    private static IResult Err(int status, Dictionary<string, string[]> errors)
        => Results.Json(new { errors }, statusCode: status);

    private static string Fmt(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc).ToString(DateFormat);

    private static string? TokenOf(HttpRequest req)
    {
        var h = req.Headers.Authorization.ToString();
        if (string.IsNullOrWhiteSpace(h)) return null;
        var i = h.IndexOf(' ');
        return i < 0 ? h : h[(i + 1)..].Trim();
    }

    /// <summary>Resolves the caller. Returns (userId, errorResult). Anonymous callers get (null, null) when optional.</summary>
    private static (int? id, IResult? error) Auth(HttpRequest req, JwtService jwt, bool required)
    {
        var token = TokenOf(req);
        if (token == null) return required ? (null, Err(401, "token", "is missing")) : (null, null);
        var id = jwt.Validate(token);
        if (id == null) return required ? (null, Err(401, "token", "is invalid")) : (null, null);
        return (id, null);
    }

    private static async Task<JsonElement?> Section(HttpRequest req, string name)
    {
        try
        {
            using var doc = await JsonDocument.ParseAsync(req.Body);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.Object)
                return el.Clone();
        }
        catch (JsonException) { }
        return null;
    }

    private static bool TryStr(JsonElement obj, string name, out string? value, out bool present)
    {
        value = null;
        present = obj.TryGetProperty(name, out var el);
        if (!present) return true;
        if (el.ValueKind == JsonValueKind.Null) return true;
        if (el.ValueKind != JsonValueKind.String) return false;
        value = el.GetString();
        return true;
    }

    private static string Slugify(string title)
    {
        var s = Regex.Replace(title.ToLowerInvariant(), @"[^a-z0-9]+", "-").Trim('-');
        return s.Length == 0 ? "article" : s.Length > 80 ? s[..80].Trim('-') : s;
    }

    private static object UserJson(User u, JwtService jwt) => new
    {
        user = new { email = u.Email, token = jwt.Create(u.Id, TokenLifetime), username = u.Username, bio = u.Bio, image = u.Image }
    };

    private static object ProfileOf(User u, bool following) => new { username = u.Username, bio = u.Bio, image = u.Image, following };

    private static bool Unique(DbUpdateException e) => e.InnerException?.Message.Contains("UNIQUE", StringComparison.OrdinalIgnoreCase) == true;

    // ---------- article serialization ----------

    private static async Task<List<object>> Shape(AppDb db, List<Article> articles, int? me, bool withBody)
    {
        var ids = articles.Select(a => a.Id).ToList();
        var counts = await db.Favorites.Where(f => ids.Contains(f.ArticleId))
            .GroupBy(f => f.ArticleId).Select(g => new { g.Key, N = g.Count() }).ToDictionaryAsync(x => x.Key, x => x.N);
        var favorited = new HashSet<int>();
        var following = new HashSet<int>();
        if (me != null)
        {
            favorited = (await db.Favorites.Where(f => f.UserId == me && ids.Contains(f.ArticleId)).Select(f => f.ArticleId).ToListAsync()).ToHashSet();
            var authorIds = articles.Select(a => a.AuthorId).Distinct().ToList();
            following = (await db.Follows.Where(f => f.FollowerId == me && authorIds.Contains(f.FolloweeId)).Select(f => f.FolloweeId).ToListAsync()).ToHashSet();
        }

        return articles.Select(a =>
        {
            var tags = a.Tags.OrderBy(t => t.Id).Select(t => t.Tag).ToList();
            var author = ProfileOf(a.Author, following.Contains(a.AuthorId));
            var count = counts.GetValueOrDefault(a.Id);
            var fav = favorited.Contains(a.Id);
            if (withBody)
                return (object)new { slug = a.Slug, title = a.Title, description = a.Description, body = a.Body, tagList = tags,
                    createdAt = Fmt(a.CreatedAt), updatedAt = Fmt(a.UpdatedAt), favorited = fav, favoritesCount = count, author };
            return new { slug = a.Slug, title = a.Title, description = a.Description, tagList = tags,
                createdAt = Fmt(a.CreatedAt), updatedAt = Fmt(a.UpdatedAt), favorited = fav, favoritesCount = count, author };
        }).ToList();
    }

    private static IQueryable<Article> WithRefs(AppDb db) => db.Articles.Include(a => a.Author).Include(a => a.Tags);

    private static async Task<IResult> SingleArticle(AppDb db, Article a, int? me, int status = 200)
        => Results.Json(new { article = (await Shape(db, [a], me, true))[0] }, statusCode: status);

    private static async Task<IResult> ListArticles(AppDb db, IQueryable<Article> q, HttpRequest req, int? me)
    {
        int Int(string k, int def, int max) =>
            int.TryParse(req.Query[k], out var v) && v >= 0 ? Math.Min(v, max) : def;
        var limit = Int("limit", 20, 100);
        var offset = Int("offset", 0, int.MaxValue);
        var total = await q.CountAsync();
        var page = await q.OrderByDescending(a => a.CreatedAt).ThenByDescending(a => a.Id)
            .Skip(offset).Take(limit).Include(a => a.Author).Include(a => a.Tags).AsSplitQuery().ToListAsync();
        return Results.Json(new { articles = await Shape(db, page, me, false), articlesCount = total });
    }

    // ---------- routes ----------

    public static void Map(WebApplication app)
    {
        // Users & auth
        app.MapPost("/api/users", async (HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var u = await Section(req, "user");
            if (u == null) return Err(422, "user", "can't be blank");
            var errors = new Dictionary<string, string[]>();
            TryStr(u.Value, "username", out var username, out _);
            TryStr(u.Value, "email", out var email, out _);
            TryStr(u.Value, "password", out var password, out _);
            if (string.IsNullOrWhiteSpace(username)) errors["username"] = ["can't be blank"];
            if (string.IsNullOrWhiteSpace(email)) errors["email"] = ["can't be blank"];
            if (string.IsNullOrEmpty(password)) errors["password"] = ["can't be blank"];
            else if (password.Length < 8) errors["password"] = ["is too short (minimum is 8 characters)"];
            if (errors.Count > 0) return Err(422, errors);

            if (await db.Users.AnyAsync(x => x.Username == username)) errors["username"] = ["has already been taken"];
            if (await db.Users.AnyAsync(x => x.Email == email)) errors["email"] = ["has already been taken"];
            if (errors.Count > 0) return Err(409, errors);

            var user = new User { Username = username!, Email = email!, PasswordHash = Security.HashPassword(password!) };
            db.Users.Add(user);
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException e) when (Unique(e)) { return Err(409, "username", "has already been taken"); }
            return Results.Json(UserJson(user, jwt), statusCode: 201);
        });

        app.MapPost("/api/users/login", async (HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var u = await Section(req, "user");
            if (u == null) return Err(422, "user", "can't be blank");
            TryStr(u.Value, "email", out var email, out _);
            TryStr(u.Value, "password", out var password, out _);
            var errors = new Dictionary<string, string[]>();
            if (string.IsNullOrWhiteSpace(email)) errors["email"] = ["can't be blank"];
            if (string.IsNullOrEmpty(password)) errors["password"] = ["can't be blank"];
            if (errors.Count > 0) return Err(422, errors);
            var user = await db.Users.FirstOrDefaultAsync(x => x.Email == email);
            if (user == null || !Security.VerifyPassword(password!, user.PasswordHash)) return Err(401, "credentials", "invalid");
            return Results.Json(UserJson(user, jwt));
        });

        app.MapGet("/api/user", async (HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var user = await db.Users.FindAsync(me);
            return user == null ? Err(401, "token", "is invalid") : Results.Json(UserJson(user, jwt));
        });

        app.MapPut("/api/user", async (HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var user = await db.Users.FindAsync(me);
            if (user == null) return Err(401, "token", "is invalid");
            var u = await Section(req, "user");
            if (u == null) return Err(422, "user", "can't be blank");

            var errors = new Dictionary<string, string[]>();
            if (!TryStr(u.Value, "username", out var username, out var hasUsername) || (hasUsername && string.IsNullOrWhiteSpace(username)))
                errors["username"] = ["can't be blank"];
            if (!TryStr(u.Value, "email", out var email, out var hasEmail) || (hasEmail && string.IsNullOrWhiteSpace(email)))
                errors["email"] = ["can't be blank"];
            if (!TryStr(u.Value, "password", out var password, out var hasPassword) || (hasPassword && string.IsNullOrEmpty(password)))
                errors["password"] = ["can't be blank"];
            else if (hasPassword && password!.Length < 8) errors["password"] = ["is too short (minimum is 8 characters)"];
            if (!TryStr(u.Value, "bio", out var bio, out var hasBio)) errors["bio"] = ["is invalid"];
            if (!TryStr(u.Value, "image", out var image, out var hasImage)) errors["image"] = ["is invalid"];
            if (errors.Count > 0) return Err(422, errors);

            if (hasUsername && username != user.Username)
            {
                if (await db.Users.AnyAsync(x => x.Username == username && x.Id != user.Id)) errors["username"] = ["has already been taken"];
                else user.Username = username!;
            }
            if (hasEmail && email != user.Email)
            {
                if (await db.Users.AnyAsync(x => x.Email == email && x.Id != user.Id)) errors["email"] = ["has already been taken"];
                else user.Email = email!;
            }
            if (errors.Count > 0) return Err(409, errors);
            if (hasPassword) user.PasswordHash = Security.HashPassword(password!);
            if (hasBio) user.Bio = string.IsNullOrEmpty(bio) ? null : bio;
            if (hasImage) user.Image = string.IsNullOrEmpty(image) ? null : image;
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException e) when (Unique(e)) { return Err(409, "username", "has already been taken"); }
            return Results.Json(UserJson(user, jwt));
        });

        // Profiles
        app.MapGet("/api/profiles/{username}", async (string username, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, _) = Auth(req, jwt, false);
            var target = await db.Users.FirstOrDefaultAsync(x => x.Username == username);
            if (target == null) return Err(404, "profile", "not found");
            var following = me != null && await db.Follows.AnyAsync(f => f.FollowerId == me && f.FolloweeId == target.Id);
            return Results.Json(new { profile = ProfileOf(target, following) });
        });

        app.MapPost("/api/profiles/{username}/follow", async (string username, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var target = await db.Users.FirstOrDefaultAsync(x => x.Username == username);
            if (target == null) return Err(404, "profile", "not found");
            if (!await db.Follows.AnyAsync(f => f.FollowerId == me && f.FolloweeId == target.Id))
            {
                db.Follows.Add(new Follow { FollowerId = me!.Value, FolloweeId = target.Id });
                try { await db.SaveChangesAsync(); } catch (DbUpdateException e) when (Unique(e)) { }
            }
            return Results.Json(new { profile = ProfileOf(target, true) });
        });

        app.MapDelete("/api/profiles/{username}/follow", async (string username, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var target = await db.Users.FirstOrDefaultAsync(x => x.Username == username);
            if (target == null) return Err(404, "profile", "not found");
            await db.Follows.Where(f => f.FollowerId == me && f.FolloweeId == target.Id).ExecuteDeleteAsync();
            return Results.Json(new { profile = ProfileOf(target, false) });
        });

        // Articles
        app.MapGet("/api/articles", async (HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, _) = Auth(req, jwt, false);
            IQueryable<Article> q = db.Articles;
            string? tag = req.Query["tag"], author = req.Query["author"], fav = req.Query["favorited"];
            if (!string.IsNullOrEmpty(tag)) q = q.Where(a => a.Tags.Any(t => t.Tag == tag));
            if (!string.IsNullOrEmpty(author)) q = q.Where(a => a.Author.Username == author);
            if (!string.IsNullOrEmpty(fav))
                q = q.Where(a => db.Favorites.Any(f => f.ArticleId == a.Id && db.Users.Any(u => u.Id == f.UserId && u.Username == fav)));
            return await ListArticles(db, q, req, me);
        });

        app.MapGet("/api/articles/feed", async (HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var q = db.Articles.Where(a => db.Follows.Any(f => f.FollowerId == me && f.FolloweeId == a.AuthorId));
            return await ListArticles(db, q, req, me);
        });

        app.MapGet("/api/articles/{slug}", async (string slug, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, _) = Auth(req, jwt, false);
            var a = await WithRefs(db).FirstOrDefaultAsync(x => x.Slug == slug);
            return a == null ? Err(404, "article", "not found") : await SingleArticle(db, a, me);
        });

        app.MapPost("/api/articles", async (HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var el = await Section(req, "article");
            if (el == null) return Err(422, "article", "can't be blank");
            TryStr(el.Value, "title", out var title, out _);
            TryStr(el.Value, "description", out var description, out _);
            TryStr(el.Value, "body", out var body, out _);
            var errors = new Dictionary<string, string[]>();
            if (string.IsNullOrWhiteSpace(title)) errors["title"] = ["can't be blank"];
            if (string.IsNullOrWhiteSpace(description)) errors["description"] = ["can't be blank"];
            if (string.IsNullOrWhiteSpace(body)) errors["body"] = ["can't be blank"];
            var tags = new List<string>();
            if (el.Value.TryGetProperty("tagList", out var tl) && tl.ValueKind != JsonValueKind.Null)
            {
                if (!ParseTags(tl, tags)) errors["tagList"] = ["is invalid"];
            }
            if (errors.Count > 0) return Err(422, errors);

            var now = DateTime.UtcNow;
            var article = new Article
            {
                Slug = await UniqueSlug(db, title!), Title = title!, Description = description!, Body = body!,
                AuthorId = me!.Value, CreatedAt = now, UpdatedAt = now,
                Tags = tags.Select(t => new ArticleTag { Tag = t }).ToList(),
            };
            db.Articles.Add(article);
            await db.SaveChangesAsync();
            article.Author = (await db.Users.FindAsync(me))!;
            return await SingleArticle(db, article, me, 201);
        });

        app.MapPut("/api/articles/{slug}", async (string slug, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var a = await WithRefs(db).FirstOrDefaultAsync(x => x.Slug == slug);
            if (a == null) return Err(404, "article", "not found");
            if (a.AuthorId != me) return Err(403, "article", "forbidden");
            var el = await Section(req, "article");
            if (el == null) return Err(422, "article", "can't be blank");

            var errors = new Dictionary<string, string[]>();
            string? Field(string name)
            {
                if (!TryStr(el.Value, name, out var v, out var present) || (present && string.IsNullOrWhiteSpace(v)))
                { errors[name] = ["can't be blank"]; return null; }
                return present ? v : null;
            }
            var title = Field("title");
            var description = Field("description");
            var body = Field("body");
            List<string>? newTags = null;
            if (el.Value.TryGetProperty("tagList", out var tl))
            {
                newTags = [];
                if (tl.ValueKind == JsonValueKind.Null || !ParseTags(tl, newTags)) errors["tagList"] = ["is invalid"];
            }
            if (errors.Count > 0) return Err(422, errors);

            if (title != null) a.Title = title;
            if (description != null) a.Description = description;
            if (body != null) a.Body = body;
            if (newTags != null)
            {
                db.ArticleTags.RemoveRange(a.Tags);
                a.Tags = newTags.Select(t => new ArticleTag { ArticleId = a.Id, Tag = t }).ToList();
            }
            a.UpdatedAt = DateTime.UtcNow;
            await db.SaveChangesAsync();
            return await SingleArticle(db, a, me);
        });

        app.MapDelete("/api/articles/{slug}", async (string slug, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var a = await db.Articles.FirstOrDefaultAsync(x => x.Slug == slug);
            if (a == null) return Err(404, "article", "not found");
            if (a.AuthorId != me) return Err(403, "article", "forbidden");
            await db.Comments.Where(c => c.ArticleId == a.Id).ExecuteDeleteAsync();
            await db.Favorites.Where(f => f.ArticleId == a.Id).ExecuteDeleteAsync();
            await db.ArticleTags.Where(t => t.ArticleId == a.Id).ExecuteDeleteAsync();
            await db.Articles.Where(x => x.Id == a.Id).ExecuteDeleteAsync();
            return Results.NoContent();
        });

        // Favorites
        app.MapPost("/api/articles/{slug}/favorite", async (string slug, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var a = await WithRefs(db).FirstOrDefaultAsync(x => x.Slug == slug);
            if (a == null) return Err(404, "article", "not found");
            if (!await db.Favorites.AnyAsync(f => f.UserId == me && f.ArticleId == a.Id))
            {
                db.Favorites.Add(new Favorite { UserId = me!.Value, ArticleId = a.Id });
                try { await db.SaveChangesAsync(); } catch (DbUpdateException e) when (Unique(e)) { }
            }
            return await SingleArticle(db, a, me);
        });

        app.MapDelete("/api/articles/{slug}/favorite", async (string slug, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var a = await WithRefs(db).FirstOrDefaultAsync(x => x.Slug == slug);
            if (a == null) return Err(404, "article", "not found");
            await db.Favorites.Where(f => f.UserId == me && f.ArticleId == a.Id).ExecuteDeleteAsync();
            return await SingleArticle(db, a, me);
        });

        // Comments
        app.MapGet("/api/articles/{slug}/comments", async (string slug, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, _) = Auth(req, jwt, false);
            var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
            if (articleId == null) return Err(404, "article", "not found");
            var comments = await db.Comments.Include(c => c.Author).Where(c => c.ArticleId == articleId)
                .OrderByDescending(c => c.CreatedAt).ThenByDescending(c => c.Id).ToListAsync();
            var authorIds = comments.Select(c => c.AuthorId).Distinct().ToList();
            var following = me == null ? new HashSet<int>()
                : (await db.Follows.Where(f => f.FollowerId == me && authorIds.Contains(f.FolloweeId)).Select(f => f.FolloweeId).ToListAsync()).ToHashSet();
            return Results.Json(new { comments = comments.Select(c => CommentJson(c, following.Contains(c.AuthorId))) });
        });

        app.MapPost("/api/articles/{slug}/comments", async (string slug, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
            if (articleId == null) return Err(404, "article", "not found");
            var el = await Section(req, "comment");
            if (el == null) return Err(422, "comment", "can't be blank");
            TryStr(el.Value, "body", out var body, out _);
            if (string.IsNullOrWhiteSpace(body)) return Err(422, "body", "can't be blank");
            var now = DateTime.UtcNow;
            var c = new Comment { ArticleId = articleId.Value, AuthorId = me!.Value, Body = body, CreatedAt = now, UpdatedAt = now };
            db.Comments.Add(c);
            await db.SaveChangesAsync();
            c.Author = (await db.Users.FindAsync(me))!;
            return Results.Json(new { comment = CommentJson(c, false) }, statusCode: 201);
        });

        app.MapPut("/api/articles/{slug}/comments/{id}", async (string slug, string id, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
            if (articleId == null) return Err(404, "article", "not found");
            var c = int.TryParse(id, out var cid) ? await db.Comments.Include(x => x.Author).FirstOrDefaultAsync(x => x.Id == cid && x.ArticleId == articleId) : null;
            if (c == null) return Err(404, "comment", "not found");
            if (c.AuthorId != me) return Err(403, "comment", "forbidden");
            var el = await Section(req, "comment");
            if (el == null) return Err(422, "body", "can't be blank");
            TryStr(el.Value, "body", out var body, out _);
            if (string.IsNullOrWhiteSpace(body)) return Err(422, "body", "can't be blank");
            c.Body = body;
            c.UpdatedAt = DateTime.UtcNow;
            await db.SaveChangesAsync();
            var following = await db.Follows.AnyAsync(f => f.FollowerId == me && f.FolloweeId == c.AuthorId);
            return Results.Json(new { comment = CommentJson(c, following) });
        });

        app.MapDelete("/api/articles/{slug}/comments/{id}", async (string slug, string id, HttpRequest req, AppDb db, JwtService jwt) =>
        {
            var (me, err) = Auth(req, jwt, true);
            if (err != null) return err;
            var articleId = await db.Articles.Where(x => x.Slug == slug).Select(x => (int?)x.Id).FirstOrDefaultAsync();
            if (articleId == null) return Err(404, "article", "not found");
            var c = int.TryParse(id, out var cid) ? await db.Comments.FirstOrDefaultAsync(x => x.Id == cid && x.ArticleId == articleId) : null;
            if (c == null) return Err(404, "comment", "not found");
            if (c.AuthorId != me) return Err(403, "comment", "forbidden");
            db.Comments.Remove(c);
            await db.SaveChangesAsync();
            return Results.NoContent();
        });

        // Tags
        app.MapGet("/api/tags", async (AppDb db) =>
        {
            var tags = await db.ArticleTags.GroupBy(t => t.Tag).OrderBy(g => g.Min(t => t.Id)).Select(g => g.Key).ToListAsync();
            return Results.Json(new { tags });
        });
    }

    private static object CommentJson(Comment c, bool following) => new
    {
        id = c.Id, createdAt = Fmt(c.CreatedAt), updatedAt = Fmt(c.UpdatedAt), body = c.Body, author = ProfileOf(c.Author, following),
    };

    private static bool ParseTags(JsonElement el, List<string> into)
    {
        if (el.ValueKind != JsonValueKind.Array) return false;
        foreach (var t in el.EnumerateArray())
        {
            if (t.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(t.GetString())) return false;
            var s = t.GetString()!;
            if (!into.Contains(s)) into.Add(s);
        }
        return true;
    }

    private static async Task<string> UniqueSlug(AppDb db, string title)
    {
        var baseSlug = Slugify(title);
        var slug = baseSlug;
        while (await db.Articles.AnyAsync(a => a.Slug == slug))
            slug = $"{baseSlug}-{Guid.NewGuid().ToString("N")[..8]}";
        return slug;
    }
}
