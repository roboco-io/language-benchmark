using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using RealWorld.Data;
using RealWorld.Infrastructure;

namespace RealWorld.Endpoints;

public static class ArticleEndpoints
{
    private const int DefaultLimit = 20;
    private const int MaxLimit = 100;

    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapGet("/api/articles", List);
        app.MapGet("/api/articles/feed", Feed);
        app.MapPost("/api/articles", Create);
        app.MapGet("/api/articles/{slug}", Get);
        app.MapPut("/api/articles/{slug}", Update);
        app.MapDelete("/api/articles/{slug}", Delete);
        app.MapPost("/api/articles/{slug}/favorite", Favorite);
        app.MapDelete("/api/articles/{slug}/favorite", Unfavorite);
        app.MapGet("/api/tags", Tags);
    }

    private sealed record ArticleRow(
        string Slug, string Title, string Description, string? Body, List<string> Tags,
        DateTime CreatedAt, DateTime UpdatedAt, bool Favorited, int FavoritesCount,
        string AuthorUsername, string? AuthorBio, string? AuthorImage, bool Following);

    private static IQueryable<ArticleRow> Project(AppDb db, IQueryable<Article> q, int? me, bool includeBody) =>
        q.Select(a => new ArticleRow(
            a.Slug, a.Title, a.Description, includeBody ? a.Body : null,
            a.Tags.OrderBy(t => t.Position).Select(t => t.Name).ToList(),
            a.CreatedAt, a.UpdatedAt,
            me != null && a.Favorites.Any(f => f.UserId == me),
            a.Favorites.Count,
            a.Author.Username, a.Author.Bio, a.Author.Image,
            me != null && db.Follows.Any(f => f.FollowerId == me && f.FolloweeId == a.AuthorId)));

    private static ArticleDto ToDto(ArticleRow r) => new(
        r.Slug, r.Title, r.Description, r.Body, r.Tags,
        Api.FormatDate(r.CreatedAt), Api.FormatDate(r.UpdatedAt), r.Favorited, r.FavoritesCount,
        new ProfileDto(r.AuthorUsername, r.AuthorBio, r.AuthorImage, r.Following));

    private static async Task<ArticleDto> LoadOne(AppDb db, int articleId, int? me)
    {
        var row = await Project(db, db.Articles.AsNoTracking().Where(a => a.Id == articleId), me, true).FirstAsync();
        return ToDto(row);
    }

    private static int ParseInt(string? s, int fallback, int max) =>
        int.TryParse(s, out var n) && n >= 0 ? Math.Min(n, max) : fallback;

    private static async Task<IResult> Page(AppDb db, IQueryable<Article> q, HttpContext ctx, int? me)
    {
        var limit = ParseInt(ctx.Request.Query["limit"], DefaultLimit, MaxLimit);
        var offset = ParseInt(ctx.Request.Query["offset"], 0, int.MaxValue);
        var count = await q.CountAsync();
        var rows = await Project(db, q.OrderByDescending(a => a.CreatedAt).ThenByDescending(a => a.Id).Skip(offset).Take(limit), me, false)
            .ToListAsync();
        return Results.Ok(new { articles = rows.Select(ToDto), articlesCount = count });
    }

    private static async Task<IResult> List(HttpContext ctx, AppDb db)
    {
        var me = Api.OptionalUser(ctx);
        var q = db.Articles.AsNoTracking();
        string? tag = ctx.Request.Query["tag"], author = ctx.Request.Query["author"], favorited = ctx.Request.Query["favorited"];
        if (!string.IsNullOrEmpty(tag)) q = q.Where(a => a.Tags.Any(t => t.Name == tag));
        if (!string.IsNullOrEmpty(author)) q = q.Where(a => a.Author.Username == author);
        if (!string.IsNullOrEmpty(favorited))
            q = q.Where(a => a.Favorites.Any(f => db.Users.Any(u => u.Id == f.UserId && u.Username == favorited)));
        return await Page(db, q, ctx, me);
    }

    private static async Task<IResult> Feed(HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var q = db.Articles.AsNoTracking()
            .Where(a => db.Follows.Any(f => f.FollowerId == me && f.FolloweeId == a.AuthorId));
        return await Page(db, q, ctx, me);
    }

    private static async Task<IResult> Get(string slug, HttpContext ctx, AppDb db)
    {
        var me = Api.OptionalUser(ctx);
        var row = await Project(db, db.Articles.AsNoTracking().Where(a => a.Slug == slug), me, true).FirstOrDefaultAsync()
                  ?? throw ApiException.NotFound("article");
        return Results.Ok(new { article = ToDto(row) });
    }

    /// <summary>Parses "tagList": returns null when absent; throws 422 when it is not an array of strings.</summary>
    private static List<string>? ParseTags(JsonObject body)
    {
        if (!body.TryGetPropertyValue("tagList", out var node)) return null;
        if (node is not JsonArray arr) throw ApiException.Of(422, "tagList", "must be an array of strings");
        var tags = new List<string>();
        foreach (var item in arr)
        {
            if (item is not JsonValue v || v.GetValueKind() != JsonValueKind.String)
                throw ApiException.Of(422, "tagList", "must be an array of strings");
            var tag = v.GetValue<string>().Trim();
            if (tag.Length > 0 && !tags.Contains(tag)) tags.Add(tag);
        }
        return tags;
    }

    private static void SetTags(Article a, List<string> tags)
    {
        // Reuse existing rows by name so EF never tracks a deleted and an added row with the same key.
        var existing = a.Tags.ToDictionary(t => t.Name);
        a.Tags.RemoveAll(t => !tags.Contains(t.Name));
        for (var i = 0; i < tags.Count; i++)
        {
            if (existing.TryGetValue(tags[i], out var tag)) tag.Position = i;
            else a.Tags.Add(new ArticleTag { Name = tags[i], Position = i });
        }
    }

    private static string Slugify(string title)
    {
        var sb = new StringBuilder();
        var dash = false;
        foreach (var ch in title.Normalize(NormalizationForm.FormKD).ToLowerInvariant())
        {
            if (char.IsAsciiLetterOrDigit(ch)) { sb.Append(ch); dash = false; }
            else if (char.IsLetterOrDigit(ch)) { sb.Append(ch); dash = false; }
            else if (!dash && sb.Length > 0 && char.GetUnicodeCategory(ch) != System.Globalization.UnicodeCategory.NonSpacingMark)
            { sb.Append('-'); dash = true; }
        }
        var slug = sb.ToString().Trim('-');
        if (slug.Length > 80) slug = slug[..80].Trim('-');
        return slug.Length == 0 ? "article" : slug;
    }

    private static async Task<string> UniqueSlug(AppDb db, string title, int? exceptId)
    {
        var baseSlug = Slugify(title);
        if (!await db.Articles.AnyAsync(a => a.Slug == baseSlug && a.Id != exceptId)) return baseSlug;
        return RandomSuffix(baseSlug);
    }

    private static string RandomSuffix(string baseSlug) => $"{baseSlug}-{Guid.NewGuid().ToString("N")[..8]}";

    /// <summary>Saves, retrying with a randomized slug if a concurrent request took the same one.</summary>
    private static async Task SaveWithSlugRetry(AppDb db, Article a, string title)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                await db.SaveChangesAsync();
                return;
            }
            catch (DbUpdateException e) when (Api.IsUniqueViolation(e) && attempt < 3)
            {
                a.Slug = RandomSuffix(Slugify(title));
            }
        }
    }

    private static async Task<IResult> Create(HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var body = await Api.ReadRoot(ctx, "article");
        var v = new Validation();
        var title = Api.RequiredString(body, "title", v);
        var description = Api.RequiredString(body, "description", v);
        var text = Api.RequiredString(body, "body", v);
        v.ThrowIfAny();
        var tags = body["tagList"] is null ? [] : ParseTags(body) ?? [];

        var now = Api.Now();
        var article = new Article
        {
            Slug = await UniqueSlug(db, title!, null),
            Title = title!, Description = description!, Body = text!,
            AuthorId = me, CreatedAt = now, UpdatedAt = now,
        };
        SetTags(article, tags);
        db.Articles.Add(article);
        await SaveWithSlugRetry(db, article, title!);
        return Results.Json(new { article = await LoadOne(db, article.Id, me) }, statusCode: 201);
    }

    private static async Task<Article> FindOwned(AppDb db, string slug, int me, bool includeTags)
    {
        var q = db.Articles.AsQueryable();
        if (includeTags) q = q.Include(a => a.Tags);
        var article = await q.FirstOrDefaultAsync(a => a.Slug == slug) ?? throw ApiException.NotFound("article");
        if (article.AuthorId != me) throw ApiException.Forbidden("article");
        return article;
    }

    private static async Task<IResult> Update(string slug, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var article = await FindOwned(db, slug, me, includeTags: true);
        var body = await Api.ReadRoot(ctx, "article");
        var v = new Validation();
        string? title = null;
        foreach (var field in new[] { "title", "description", "body" })
        {
            if (!Api.TryGetString(body, field, out var value, out _)) continue;
            if (string.IsNullOrWhiteSpace(value)) { v.Add(field, Api.Blank); continue; }
            switch (field)
            {
                case "title": title = value; break;
                case "description": article.Description = value; break;
                default: article.Body = value; break;
            }
        }
        v.ThrowIfAny();
        var tags = ParseTags(body);

        if (title != null && title != article.Title)
        {
            article.Title = title;
            article.Slug = await UniqueSlug(db, title, article.Id);
        }
        if (tags != null) SetTags(article, tags);
        var now = Api.Now();
        article.UpdatedAt = now > article.UpdatedAt ? now : article.UpdatedAt.AddMilliseconds(1);
        await SaveWithSlugRetry(db, article, article.Title);
        return Results.Ok(new { article = await LoadOne(db, article.Id, me) });
    }

    private static async Task<IResult> Delete(string slug, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var article = await FindOwned(db, slug, me, includeTags: false);
        await db.Articles.Where(a => a.Id == article.Id).ExecuteDeleteAsync();
        return Results.NoContent();
    }

    private static async Task<IResult> Favorite(string slug, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var id = await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync()
                 ?? throw ApiException.NotFound("article");
        if (!await db.Favorites.AnyAsync(f => f.UserId == me && f.ArticleId == id))
        {
            db.Favorites.Add(new Favorite { UserId = me, ArticleId = id });
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateException e) when (Api.IsUniqueViolation(e)) { /* favorited concurrently */ }
        }
        return Results.Ok(new { article = await LoadOne(db, id, me) });
    }

    private static async Task<IResult> Unfavorite(string slug, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var id = await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync()
                 ?? throw ApiException.NotFound("article");
        await db.Favorites.Where(f => f.UserId == me && f.ArticleId == id).ExecuteDeleteAsync();
        return Results.Ok(new { article = await LoadOne(db, id, me) });
    }

    private static async Task<IResult> Tags(AppDb db)
    {
        var tags = await db.ArticleTags.AsNoTracking().Select(t => t.Name).Distinct().OrderBy(n => n).ToListAsync();
        return Results.Ok(new { tags });
    }
}
