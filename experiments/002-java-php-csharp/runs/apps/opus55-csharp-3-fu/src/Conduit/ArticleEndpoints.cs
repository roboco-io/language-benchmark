using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;

namespace Conduit;

public static partial class ArticleEndpoints
{
    public static void Map(WebApplication app)
    {
        app.MapGet("/api/articles", List);
        app.MapGet("/api/articles/feed", Feed);
        app.MapPost("/api/articles", Create);
        app.MapGet("/api/articles/{slug}", Get);
        app.MapPut("/api/articles/{slug}", Update);
        app.MapDelete("/api/articles/{slug}", Delete);
        app.MapPost("/api/articles/{slug}/favorite", Favorite);
        app.MapDelete("/api/articles/{slug}/favorite", Unfavorite);
        app.MapGet("/api/articles/{slug}/comments", ListComments);
        app.MapPost("/api/articles/{slug}/comments", AddComment);
        app.MapPut("/api/articles/{slug}/comments/{id}", UpdateComment);
        app.MapDelete("/api/articles/{slug}/comments/{id}", DeleteComment);
        app.MapGet("/api/tags", ListTags);
    }

    private sealed record ArticleRow(
        Article Article, User Author, List<string> Tags, int FavoritesCount, bool Favorited, bool Following);

    /// <summary>Loads articles together with tags, favorite state and author follow state in one query.</summary>
    private static Task<List<ArticleRow>> Project(IQueryable<Article> q, int? uid) =>
        q.Select(a => new ArticleRow(
            a,
            a.Author,
            a.Tags.OrderBy(t => t.Position).Select(t => t.Tag.Name).ToList(),
            a.Favorites.Count,
            uid != null && a.Favorites.Any(f => f.UserId == uid),
            uid != null && a.Author.Followers.Any(f => f.FollowerId == uid)))
        .AsNoTracking()
        .ToListAsync();

    private static Dictionary<string, object?> ArticleJson(ArticleRow r, bool includeBody)
    {
        var json = new Dictionary<string, object?>
        {
            ["slug"] = r.Article.Slug,
            ["title"] = r.Article.Title,
            ["description"] = r.Article.Description,
        };
        if (includeBody) json["body"] = r.Article.Body;
        json["tagList"] = r.Tags;
        json["createdAt"] = Formats.Iso(r.Article.CreatedAt);
        json["updatedAt"] = Formats.Iso(r.Article.UpdatedAt);
        json["favorited"] = r.Favorited;
        json["favoritesCount"] = r.FavoritesCount;
        json["author"] = UserEndpoints.ProfileJson(r.Author, r.Following);
        return json;
    }

    private static async Task<IResult> Single(AppDb db, int articleId, int? uid, int status = 200)
    {
        var rows = await Project(db.Articles.Where(a => a.Id == articleId), uid);
        return rows.Count == 0
            ? Errors.NotFound("article")
            : Results.Json(new { article = ArticleJson(rows[0], true) }, statusCode: status);
    }

    private static (int Limit, int Offset) Paging(HttpRequest req)
    {
        var limit = int.TryParse(req.Query["limit"], out var l) && l >= 0 ? Math.Min(l, 1000) : 20;
        var offset = int.TryParse(req.Query["offset"], out var o) && o >= 0 ? o : 0;
        return (limit, offset);
    }

    private static async Task<IResult> Page(AppDb db, HttpRequest req, IQueryable<Article> q, int? uid)
    {
        var (limit, offset) = Paging(req);
        var count = await q.CountAsync();
        var rows = await Project(q.OrderByDescending(a => a.CreatedAt).ThenByDescending(a => a.Id).Skip(offset).Take(limit), uid);
        return Results.Json(new { articles = rows.Select(r => ArticleJson(r, false)), articlesCount = count });
    }

    private static async Task<IResult> List(HttpContext ctx, AppDb db)
    {
        var (state, uid) = ctx.Authenticate();
        if (state == AuthState.Invalid) return Errors.Unauthorized(state);

        IQueryable<Article> q = db.Articles;
        string? tag = ctx.Request.Query["tag"], author = ctx.Request.Query["author"], favorited = ctx.Request.Query["favorited"];
        if (!string.IsNullOrEmpty(tag)) q = q.Where(a => a.Tags.Any(t => t.Tag.Name == tag));
        if (!string.IsNullOrEmpty(author)) q = q.Where(a => a.Author.Username == author);
        if (!string.IsNullOrEmpty(favorited))
            q = q.Where(a => db.Favorites.Any(f => f.ArticleId == a.Id && db.Users.Any(u => u.Id == f.UserId && u.Username == favorited)));
        return await Page(db, ctx.Request, q, uid);
    }

    private static async Task<IResult> Feed(HttpContext ctx, AppDb db)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var q = db.Articles.Where(a => db.Follows.Any(f => f.FollowerId == uid && f.FolloweeId == a.AuthorId));
        return await Page(db, ctx.Request, q, uid);
    }

    private static async Task<IResult> Get(HttpContext ctx, AppDb db, string slug)
    {
        var (state, uid) = ctx.Authenticate();
        if (state == AuthState.Invalid) return Errors.Unauthorized(state);
        var rows = await Project(db.Articles.Where(a => a.Slug == slug), uid);
        return rows.Count == 0 ? Errors.NotFound("article") : Results.Json(new { article = ArticleJson(rows[0], true) });
    }

    /// <summary>Validates a tagList value. Returns null on success, or the parsed list via <paramref name="tags"/>.</summary>
    private static bool TryParseTags(Field field, out List<string> tags)
    {
        tags = [];
        if (!field.Present) return true;
        if (field.Value.ValueKind != JsonValueKind.Array) return false;
        foreach (var item in field.Value.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.String) return false;
            var name = item.GetString()!.Trim();
            if (name.Length > 0 && !tags.Contains(name)) tags.Add(name);
        }
        return true;
    }

    private static async Task SetTags(AppDb db, Article article, List<string> names)
    {
        var existing = await db.Tags.Where(t => names.Contains(t.Name)).ToDictionaryAsync(t => t.Name);
        article.Tags.Clear();
        for (var i = 0; i < names.Count; i++)
        {
            if (!existing.TryGetValue(names[i], out var tag))
            {
                tag = new Tag { Name = names[i] };
                db.Tags.Add(tag);
                existing[names[i]] = tag;
            }
            article.Tags.Add(new ArticleTag { Tag = tag, Position = i });
        }
    }

    [GeneratedRegex("[^a-z0-9]+")]
    private static partial Regex NonSlugChars();

    private static async Task<string> UniqueSlug(AppDb db, string title)
    {
        var normalized = title.Normalize(NormalizationForm.FormD);
        var ascii = new string(normalized.Where(c => c < 128).ToArray()).ToLowerInvariant();
        var baseSlug = NonSlugChars().Replace(ascii, "-").Trim('-');
        if (baseSlug.Length > 80) baseSlug = baseSlug[..80].Trim('-');
        if (baseSlug.Length == 0) baseSlug = "article";

        var slug = baseSlug;
        while (await db.Articles.AnyAsync(a => a.Slug == slug))
            slug = $"{baseSlug}-{Guid.NewGuid().ToString("N")[..8]}";
        return slug;
    }

    private static async Task<IResult> Create(HttpContext ctx, AppDb db)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var body = await Body.Read(ctx, "article");
        if (body is null) return Body.Invalid("article");

        var errors = new Dictionary<string, List<string>>();
        foreach (var name in new[] { "title", "description", "body" })
            if (!body[name].Present || body[name].IsBlankOrInvalid)
                errors[name] = ["can't be blank"];
        if (!TryParseTags(body["tagList"], out var tags)) errors["tagList"] = ["is invalid"];
        if (errors.Count > 0) return Errors.Of(422, errors);

        var now = Formats.Now();
        var article = new Article
        {
            Title = body["title"].Str!.Trim(),
            Description = body["description"].Str!,
            Body = body["body"].Str!,
            AuthorId = uid!.Value,
            CreatedAt = now,
            UpdatedAt = now,
        };
        article.Slug = await UniqueSlug(db, article.Title);
        await SetTags(db, article, tags);
        db.Articles.Add(article);
        await db.SaveChangesAsync();
        return await Single(db, article.Id, uid, 201);
    }

    private static async Task<IResult> Update(HttpContext ctx, AppDb db, string slug)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var article = await db.Articles.Include(a => a.Tags).FirstOrDefaultAsync(a => a.Slug == slug);
        if (article is null) return Errors.NotFound("article");
        if (article.AuthorId != uid) return Errors.Forbidden("article");

        var body = await Body.Read(ctx, "article");
        if (body is null) return Body.Invalid("article");

        var errors = new Dictionary<string, List<string>>();
        foreach (var name in new[] { "title", "description", "body" })
            if (body[name].IsBlankOrInvalid)
                errors[name] = ["can't be blank"];
        if (!TryParseTags(body["tagList"], out var tags)) errors["tagList"] = ["is invalid"];
        if (errors.Count > 0) return Errors.Of(422, errors);

        if (body["title"].IsString)
        {
            var title = body["title"].Str!.Trim();
            if (title != article.Title)
            {
                article.Title = title;
                article.Slug = await UniqueSlug(db, title);
            }
        }
        if (body["description"].IsString) article.Description = body["description"].Str!;
        if (body["body"].IsString) article.Body = body["body"].Str!;
        if (body["tagList"].Present)
        {
            db.ArticleTags.RemoveRange(article.Tags);
            await db.SaveChangesAsync();
            await SetTags(db, article, tags);
        }
        article.UpdatedAt = Formats.Now();
        await db.SaveChangesAsync();
        return await Single(db, article.Id, uid);
    }

    private static async Task<IResult> Delete(HttpContext ctx, AppDb db, string slug)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var article = await db.Articles.AsNoTracking().FirstOrDefaultAsync(a => a.Slug == slug);
        if (article is null) return Errors.NotFound("article");
        if (article.AuthorId != uid) return Errors.Forbidden("article");
        // Tags, favorites and comments are removed by ON DELETE CASCADE.
        await db.Articles.Where(a => a.Id == article.Id).ExecuteDeleteAsync();
        return Results.NoContent();
    }

    private static Task<IResult> Favorite(HttpContext ctx, AppDb db, string slug) => SetFavorite(ctx, db, slug, true);
    private static Task<IResult> Unfavorite(HttpContext ctx, AppDb db, string slug) => SetFavorite(ctx, db, slug, false);

    private static async Task<IResult> SetFavorite(HttpContext ctx, AppDb db, string slug, bool favorite)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var articleId = await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync();
        if (articleId is null) return Errors.NotFound("article");

        var existing = await db.Favorites.FindAsync(uid!.Value, articleId.Value);
        if (favorite && existing is null)
        {
            db.Favorites.Add(new Favorite { UserId = uid.Value, ArticleId = articleId.Value });
            try { await db.SaveChangesAsync(); } catch (DbUpdateException) { /* concurrent favorite: already exists */ }
        }
        else if (!favorite && existing is not null)
        {
            db.Favorites.Remove(existing);
            await db.SaveChangesAsync();
        }
        return await Single(db, articleId.Value, uid);
    }

    private static object CommentJson(Comment c, bool following) => new
    {
        id = c.Id,
        createdAt = Formats.Iso(c.CreatedAt),
        updatedAt = Formats.Iso(c.UpdatedAt),
        body = c.Body,
        author = UserEndpoints.ProfileJson(c.Author, following),
    };

    private static async Task<IResult> ListComments(HttpContext ctx, AppDb db, string slug)
    {
        var (state, uid) = ctx.Authenticate();
        if (state == AuthState.Invalid) return Errors.Unauthorized(state);
        var articleId = await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync();
        if (articleId is null) return Errors.NotFound("article");

        var rows = await db.Comments.AsNoTracking()
            .Where(c => c.ArticleId == articleId)
            .OrderBy(c => c.CreatedAt).ThenBy(c => c.Id)
            .Select(c => new { Comment = c, c.Author, Following = uid != null && c.Author.Followers.Any(f => f.FollowerId == uid) })
            .ToListAsync();
        foreach (var r in rows) r.Comment.Author = r.Author;
        return Results.Json(new { comments = rows.Select(r => CommentJson(r.Comment, r.Following)) });
    }

    private static async Task<IResult> AddComment(HttpContext ctx, AppDb db, string slug)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var articleId = await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync();
        if (articleId is null) return Errors.NotFound("article");

        var body = await Body.Read(ctx, "comment");
        if (body is null) return Body.Invalid("comment");
        if (!body["body"].Present || body["body"].IsBlankOrInvalid) return Errors.Blank("body");

        var author = await db.Users.FindAsync(uid);
        if (author is null) return Errors.Unauthorized(AuthState.Invalid);
        var now = Formats.Now();
        var comment = new Comment { Body = body["body"].Str!, ArticleId = articleId.Value, AuthorId = author.Id, Author = author, CreatedAt = now, UpdatedAt = now };
        db.Comments.Add(comment);
        await db.SaveChangesAsync();
        return Results.Json(new { comment = CommentJson(comment, false) }, statusCode: 201);
    }

    private static async Task<IResult> UpdateComment(HttpContext ctx, AppDb db, string slug, string id)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var articleId = await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync();
        if (articleId is null) return Errors.NotFound("article");
        if (!int.TryParse(id, out var commentId)) return Errors.NotFound("comment");

        var comment = await db.Comments.Include(c => c.Author).FirstOrDefaultAsync(c => c.Id == commentId && c.ArticleId == articleId);
        if (comment is null) return Errors.NotFound("comment");
        if (comment.AuthorId != uid) return Errors.Forbidden("comment");

        var body = await Body.Read(ctx, "comment");
        if (body is null) return Body.Invalid("comment");
        if (!body["body"].Present || body["body"].IsBlankOrInvalid) return Errors.Blank("body");

        // Only the body changes; id and createdAt are preserved.
        comment.Body = body["body"].Str!;
        comment.UpdatedAt = Formats.Now();
        await db.SaveChangesAsync();
        return Results.Json(new { comment = CommentJson(comment, false) });
    }

    private static async Task<IResult> DeleteComment(HttpContext ctx, AppDb db, string slug, string id)
    {
        var (state, uid) = ctx.Authenticate();
        if (state != AuthState.Valid) return Errors.Unauthorized(state);
        var articleId = await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync();
        if (articleId is null) return Errors.NotFound("article");
        if (!int.TryParse(id, out var commentId)) return Errors.NotFound("comment");

        var comment = await db.Comments.AsNoTracking().FirstOrDefaultAsync(c => c.Id == commentId && c.ArticleId == articleId);
        if (comment is null) return Errors.NotFound("comment");
        if (comment.AuthorId != uid) return Errors.Forbidden("comment");
        await db.Comments.Where(c => c.Id == commentId).ExecuteDeleteAsync();
        return Results.NoContent();
    }

    private static async Task<IResult> ListTags(AppDb db)
    {
        var tags = await db.Tags.AsNoTracking()
            .Where(t => db.ArticleTags.Any(at => at.TagId == t.Id))
            .OrderBy(t => t.Name)
            .Select(t => t.Name)
            .ToListAsync();
        return Results.Json(new { tags });
    }
}
