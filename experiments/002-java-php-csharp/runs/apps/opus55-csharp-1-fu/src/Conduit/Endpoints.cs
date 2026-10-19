using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;

namespace Conduit;

public static partial class Endpoints
{
    private const int MinPasswordLength = 8;

    public static void Map(WebApplication app)
    {
        var api = app.MapGroup("/api");

        api.MapPost("/users", Register);
        api.MapPost("/users/login", Login);
        api.MapGet("/user", GetCurrentUser);
        api.MapPut("/user", UpdateUser);

        api.MapGet("/profiles/{username}", GetProfile);
        api.MapPost("/profiles/{username}/follow", Follow);
        api.MapDelete("/profiles/{username}/follow", Unfollow);

        api.MapGet("/articles", ListArticles);
        api.MapGet("/articles/feed", Feed);
        api.MapPost("/articles", CreateArticle);
        api.MapGet("/articles/{slug}", GetArticle);
        api.MapPut("/articles/{slug}", UpdateArticle);
        api.MapDelete("/articles/{slug}", DeleteArticle);

        api.MapPost("/articles/{slug}/favorite", Favorite);
        api.MapDelete("/articles/{slug}/favorite", Unfavorite);

        api.MapGet("/articles/{slug}/comments", ListComments);
        api.MapPost("/articles/{slug}/comments", AddComment);
        api.MapPut("/articles/{slug}/comments/{id}", UpdateComment);
        api.MapDelete("/articles/{slug}/comments/{id}", DeleteComment);

        api.MapGet("/tags", ListTags);
    }

    // ---------------------------------------------------------------- users

    private static async Task<IResult> Register(HttpRequest req, AppDb db, TokenService tokens)
    {
        var body = await Api.ReadBody(req, "user");
        var username = Api.Required(body, "username").Trim();
        var email = Api.Required(body, "email").Trim();
        var password = Api.Required(body, "password");
        if (password.Length < MinPasswordLength)
            return Api.Error(422, "password", $"is too short (minimum is {MinPasswordLength} characters)");

        if (await db.Users.AnyAsync(u => u.Username == username))
            return Api.Error(409, "username", "has already been taken");
        if (await db.Users.AnyAsync(u => u.Email == email))
            return Api.Error(409, "email", "has already been taken");

        var user = new User { Username = username, Email = email, PasswordHash = Passwords.Hash(password) };
        db.Users.Add(user);
        if (!await TrySave(db)) return await DuplicateUserError(db, username, email, 0);

        return Results.Json(new { user = ToUserDto(user, tokens) }, statusCode: 201);
    }

    private static async Task<IResult> Login(HttpRequest req, AppDb db, TokenService tokens)
    {
        var body = await Api.ReadBody(req, "user");
        var email = Api.Required(body, "email").Trim();
        var password = Api.Required(body, "password");

        var user = await db.Users.FirstOrDefaultAsync(u => u.Email == email);
        if (user is null || !Passwords.Verify(user.PasswordHash, password))
            return Api.Error(401, "credentials", "invalid");

        return Results.Ok(new { user = ToUserDto(user, tokens) });
    }

    private static async Task<IResult> GetCurrentUser(HttpRequest req, AppDb db, TokenService tokens)
    {
        var (user, error) = await RequireUser(req, db, tokens);
        if (error is not null) return error;
        return Results.Ok(new { user = ToUserDto(user!, tokens) });
    }

    private static async Task<IResult> UpdateUser(HttpRequest req, AppDb db, TokenService tokens)
    {
        var (user, error) = await RequireUser(req, db, tokens);
        if (error is not null) return error;

        var body = await Api.ReadBody(req, "user");
        var username = Api.OptionalNonBlank(body, "username")?.Trim();
        var email = Api.OptionalNonBlank(body, "email")?.Trim();
        var password = Api.OptionalNonBlank(body, "password");
        if (password is not null && password.Length < MinPasswordLength)
            return Api.Error(422, "password", $"is too short (minimum is {MinPasswordLength} characters)");
        var (bioPresent, bio) = Api.Field(body, "bio");
        var (imagePresent, image) = Api.Field(body, "image");

        if (username is not null && username != user!.Username &&
            await db.Users.AnyAsync(u => u.Username == username))
            return Api.Error(409, "username", "has already been taken");
        if (email is not null && email != user!.Email &&
            await db.Users.AnyAsync(u => u.Email == email))
            return Api.Error(409, "email", "has already been taken");

        if (username is not null) user!.Username = username;
        if (email is not null) user!.Email = email;
        if (password is not null) user!.PasswordHash = Passwords.Hash(password);
        // Empty strings are normalized to null for the nullable profile fields.
        if (bioPresent) user!.Bio = string.IsNullOrEmpty(bio) ? null : bio;
        if (imagePresent) user!.Image = string.IsNullOrEmpty(image) ? null : image;

        if (!await TrySave(db)) return await DuplicateUserError(db, user!.Username, user.Email, user.Id);
        return Results.Ok(new { user = ToUserDto(user!, tokens) });
    }

    // ------------------------------------------------------------- profiles

    private static async Task<IResult> GetProfile(string username, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (viewerId, error) = await OptionalUserId(req, tokens);
        if (error is not null) return error;

        var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Username == username);
        if (target is null) return Api.Error(404, "profile", "not found");

        var following = viewerId is not null &&
            await db.Follows.AnyAsync(f => f.FollowerId == viewerId && f.FolloweeId == target.Id);
        return Results.Ok(new { profile = new ProfileDto(target.Username, target.Bio, target.Image, following) });
    }

    private static Task<IResult> Follow(string username, HttpRequest req, AppDb db, TokenService tokens) =>
        SetFollow(username, req, db, tokens, follow: true);

    private static Task<IResult> Unfollow(string username, HttpRequest req, AppDb db, TokenService tokens) =>
        SetFollow(username, req, db, tokens, follow: false);

    private static async Task<IResult> SetFollow(string username, HttpRequest req, AppDb db, TokenService tokens, bool follow)
    {
        var (viewerId, error) = await RequireUserId(req, tokens);
        if (error is not null) return error;

        var target = await db.Users.AsNoTracking().FirstOrDefaultAsync(u => u.Username == username);
        if (target is null) return Api.Error(404, "profile", "not found");

        var existing = await db.Follows.FindAsync(viewerId, target.Id);
        if (follow && existing is null)
        {
            db.Follows.Add(new Follow { FollowerId = viewerId, FolloweeId = target.Id });
            await TrySave(db); // A concurrent duplicate follow is harmless.
        }
        else if (!follow && existing is not null)
        {
            db.Follows.Remove(existing);
            await db.SaveChangesAsync();
        }
        return Results.Ok(new { profile = new ProfileDto(target.Username, target.Bio, target.Image, follow) });
    }

    // ------------------------------------------------------------- articles

    private static async Task<IResult> ListArticles(
        HttpRequest req, AppDb db, TokenService tokens,
        string? tag, string? author, string? favorited, string? limit, string? offset)
    {
        var (viewerId, error) = await OptionalUserId(req, tokens);
        if (error is not null) return error;

        IQueryable<Article> query = db.Articles.AsNoTracking();
        if (!string.IsNullOrEmpty(tag)) query = query.Where(a => a.Tags.Any(t => t.Name == tag));
        if (!string.IsNullOrEmpty(author)) query = query.Where(a => a.Author.Username == author);
        if (!string.IsNullOrEmpty(favorited))
            query = query.Where(a => db.Favorites.Any(f => f.ArticleId == a.Id &&
                db.Users.Any(u => u.Id == f.UserId && u.Username == favorited)));

        return await Page(query, db, viewerId, limit, offset);
    }

    private static async Task<IResult> Feed(HttpRequest req, AppDb db, TokenService tokens, string? limit, string? offset)
    {
        var (viewerId, error) = await RequireUserId(req, tokens);
        if (error is not null) return error;

        var query = db.Articles.AsNoTracking()
            .Where(a => db.Follows.Any(f => f.FollowerId == viewerId && f.FolloweeId == a.AuthorId));
        return await Page(query, db, viewerId, limit, offset);
    }

    private static async Task<IResult> Page(IQueryable<Article> query, AppDb db, int? viewerId, string? limit, string? offset)
    {
        var take = ParseNonNegative(limit, 20);
        var skip = ParseNonNegative(offset, 0);

        var count = await query.CountAsync();
        var rows = await Project(query.OrderByDescending(a => a.Id).Skip(skip).Take(take), db, viewerId, withBody: false)
            .ToListAsync();
        var articles = rows.Select(ToArticleDto).ToList();
        return Results.Ok(new { articles, articlesCount = count });
    }

    private static async Task<IResult> GetArticle(string slug, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (viewerId, error) = await OptionalUserId(req, tokens);
        if (error is not null) return error;

        var article = await LoadArticleDto(db, a => a.Slug == slug, viewerId);
        return article is null ? ArticleNotFound() : Results.Ok(new { article });
    }

    private static async Task<IResult> CreateArticle(HttpRequest req, AppDb db, TokenService tokens)
    {
        var (userId, error) = await RequireUserId(req, tokens);
        if (error is not null) return error;

        var body = await Api.ReadBody(req, "article");
        var title = Api.Required(body, "title");
        var description = Api.Required(body, "description");
        var content = Api.Required(body, "body");
        var tags = Api.Tags(body) ?? [];

        var now = DateTime.UtcNow;
        var article = new Article
        {
            Title = title,
            Description = description,
            Body = content,
            CreatedAt = now,
            UpdatedAt = now,
            AuthorId = userId,
            Tags = tags.Select((t, i) => new ArticleTag { Name = t, Position = i }).ToList(),
        };
        db.Articles.Add(article);
        await SaveWithUniqueSlug(db, article);

        var dto = await LoadArticleDto(db, a => a.Id == article.Id, userId);
        return Results.Json(new { article = dto }, statusCode: 201);
    }

    private static async Task<IResult> UpdateArticle(string slug, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (userId, error) = await RequireUserId(req, tokens);
        if (error is not null) return error;

        var article = await db.Articles.Include(a => a.Tags).FirstOrDefaultAsync(a => a.Slug == slug);
        if (article is null) return ArticleNotFound();
        if (article.AuthorId != userId) return Api.Error(403, "article", "forbidden");

        var body = await Api.ReadBody(req, "article");
        var title = Api.OptionalNonBlank(body, "title");
        var description = Api.OptionalNonBlank(body, "description");
        var content = Api.OptionalNonBlank(body, "body");
        var tags = Api.Tags(body);

        if (description is not null) article.Description = description;
        if (content is not null) article.Body = content;
        if (tags is not null)
        {
            db.ArticleTags.RemoveRange(article.Tags);
            await db.SaveChangesAsync();
            db.ArticleTags.AddRange(tags.Select((t, i) => new ArticleTag { ArticleId = article.Id, Name = t, Position = i }));
        }
        article.UpdatedAt = DateTime.UtcNow;

        if (title is not null && title != article.Title)
        {
            article.Title = title;
            await SaveWithUniqueSlug(db, article);
        }
        else
        {
            await db.SaveChangesAsync();
        }

        var dto = await LoadArticleDto(db, a => a.Id == article.Id, userId);
        return Results.Ok(new { article = dto });
    }

    private static async Task<IResult> DeleteArticle(string slug, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (userId, error) = await RequireUserId(req, tokens);
        if (error is not null) return error;

        var article = await db.Articles.AsNoTracking()
            .Where(a => a.Slug == slug).Select(a => new { a.Id, a.AuthorId }).FirstOrDefaultAsync();
        if (article is null) return ArticleNotFound();
        if (article.AuthorId != userId) return Api.Error(403, "article", "forbidden");

        // Tags, favorites and comments are removed by ON DELETE CASCADE.
        await db.Articles.Where(a => a.Id == article.Id).ExecuteDeleteAsync();
        return Results.NoContent();
    }

    // ------------------------------------------------------------ favorites

    private static Task<IResult> Favorite(string slug, HttpRequest req, AppDb db, TokenService tokens) =>
        SetFavorite(slug, req, db, tokens, favorite: true);

    private static Task<IResult> Unfavorite(string slug, HttpRequest req, AppDb db, TokenService tokens) =>
        SetFavorite(slug, req, db, tokens, favorite: false);

    private static async Task<IResult> SetFavorite(string slug, HttpRequest req, AppDb db, TokenService tokens, bool favorite)
    {
        var (userId, error) = await RequireUserId(req, tokens);
        if (error is not null) return error;

        var articleId = await ArticleIdBySlug(db, slug);
        if (articleId is null) return ArticleNotFound();

        var existing = await db.Favorites.FindAsync(userId, articleId.Value);
        if (favorite && existing is null)
        {
            db.Favorites.Add(new Favorite { UserId = userId, ArticleId = articleId.Value });
            await TrySave(db); // A concurrent duplicate favorite is harmless.
        }
        else if (!favorite && existing is not null)
        {
            db.Favorites.Remove(existing);
            await db.SaveChangesAsync();
        }

        var dto = await LoadArticleDto(db, a => a.Id == articleId.Value, userId);
        return dto is null ? ArticleNotFound() : Results.Ok(new { article = dto });
    }

    // ------------------------------------------------------------- comments

    private static async Task<IResult> ListComments(string slug, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (viewerId, error) = await OptionalUserId(req, tokens);
        if (error is not null) return error;

        var articleId = await ArticleIdBySlug(db, slug);
        if (articleId is null) return ArticleNotFound();

        var rows = await db.Comments.AsNoTracking()
            .Where(c => c.ArticleId == articleId.Value)
            .OrderBy(c => c.Id)
            .Select(c => new
            {
                c.Id, c.Body, c.CreatedAt, c.UpdatedAt,
                c.Author.Username, c.Author.Bio, c.Author.Image,
                Following = viewerId != null && db.Follows.Any(f => f.FollowerId == viewerId && f.FolloweeId == c.AuthorId),
            })
            .ToListAsync();

        var comments = rows.Select(c => new CommentDto(c.Id, Api.Timestamp(c.CreatedAt), Api.Timestamp(c.UpdatedAt), c.Body,
            new ProfileDto(c.Username, c.Bio, c.Image, c.Following))).ToList();
        return Results.Ok(new { comments });
    }

    private static async Task<IResult> AddComment(string slug, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (user, error) = await RequireUser(req, db, tokens);
        if (error is not null) return error;

        var articleId = await ArticleIdBySlug(db, slug);
        if (articleId is null) return ArticleNotFound();

        var body = await Api.ReadBody(req, "comment");
        var content = Api.Required(body, "body");

        var now = DateTime.UtcNow;
        var comment = new Comment { Body = content, ArticleId = articleId.Value, AuthorId = user!.Id, CreatedAt = now, UpdatedAt = now };
        db.Comments.Add(comment);
        await db.SaveChangesAsync();

        var dto = new CommentDto(comment.Id, Api.Timestamp(now), Api.Timestamp(now), comment.Body,
            new ProfileDto(user.Username, user.Bio, user.Image, false));
        return Results.Json(new { comment = dto }, statusCode: 201);
    }

    private static async Task<IResult> UpdateComment(string slug, string id, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (user, error) = await RequireUser(req, db, tokens);
        if (error is not null) return error;

        var articleId = await ArticleIdBySlug(db, slug);
        if (articleId is null) return ArticleNotFound();

        if (!int.TryParse(id, NumberStyles.None, CultureInfo.InvariantCulture, out var commentId))
            return Api.Error(404, "comment", "not found");
        var comment = await db.Comments
            .FirstOrDefaultAsync(c => c.Id == commentId && c.ArticleId == articleId.Value);
        if (comment is null) return Api.Error(404, "comment", "not found");
        if (comment.AuthorId != user!.Id) return Api.Error(403, "comment", "forbidden");

        var body = await Api.ReadBody(req, "comment");
        comment.Body = Api.Required(body, "body");
        comment.UpdatedAt = DateTime.UtcNow;
        await db.SaveChangesAsync();

        var dto = new CommentDto(comment.Id, Api.Timestamp(comment.CreatedAt), Api.Timestamp(comment.UpdatedAt), comment.Body,
            new ProfileDto(user.Username, user.Bio, user.Image, false));
        return Results.Ok(new { comment = dto });
    }

    private static async Task<IResult> DeleteComment(string slug, string id, HttpRequest req, AppDb db, TokenService tokens)
    {
        var (userId, error) = await RequireUserId(req, tokens);
        if (error is not null) return error;

        var articleId = await ArticleIdBySlug(db, slug);
        if (articleId is null) return ArticleNotFound();

        if (!int.TryParse(id, NumberStyles.None, CultureInfo.InvariantCulture, out var commentId))
            return Api.Error(404, "comment", "not found");
        var comment = await db.Comments.AsNoTracking()
            .Where(c => c.Id == commentId && c.ArticleId == articleId.Value)
            .Select(c => new { c.Id, c.AuthorId })
            .FirstOrDefaultAsync();
        if (comment is null) return Api.Error(404, "comment", "not found");
        if (comment.AuthorId != userId) return Api.Error(403, "comment", "forbidden");

        await db.Comments.Where(c => c.Id == comment.Id).ExecuteDeleteAsync();
        return Results.NoContent();
    }

    // ----------------------------------------------------------------- tags

    private static async Task<IResult> ListTags(AppDb db)
    {
        var tags = await db.ArticleTags.AsNoTracking().Select(t => t.Name).Distinct().ToListAsync();
        return Results.Ok(new { tags });
    }

    // -------------------------------------------------------------- helpers

    private static IResult ArticleNotFound() => Api.Error(404, "article", "not found");

    private static UserDto ToUserDto(User user, TokenService tokens) =>
        new(user.Email, tokens.Create(user), user.Username, user.Bio, user.Image);

    private static async Task<(int? UserId, IResult? Error)> OptionalUserId(HttpRequest req, TokenService tokens)
    {
        var (state, id) = await tokens.ReadAsync(req);
        return state switch
        {
            AuthState.Missing => (null, null),
            AuthState.Valid => (id, null),
            _ => (null, Api.Unauthorized(state)),
        };
    }

    private static async Task<(int UserId, IResult? Error)> RequireUserId(HttpRequest req, TokenService tokens)
    {
        var (state, id) = await tokens.ReadAsync(req);
        return state == AuthState.Valid ? (id, null) : (0, Api.Unauthorized(state));
    }

    private static async Task<(User? User, IResult? Error)> RequireUser(HttpRequest req, AppDb db, TokenService tokens)
    {
        var (id, error) = await RequireUserId(req, tokens);
        if (error is not null) return (null, error);
        var user = await db.Users.FindAsync(id);
        return user is null ? (null, Api.Unauthorized(AuthState.Invalid)) : (user, null);
    }

    private static Task<int?> ArticleIdBySlug(AppDb db, string slug) =>
        db.Articles.AsNoTracking().Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync();

    private static async Task<ArticleDto?> LoadArticleDto(AppDb db, System.Linq.Expressions.Expression<Func<Article, bool>> predicate, int? viewerId)
    {
        var row = await Project(db.Articles.AsNoTracking().Where(predicate), db, viewerId, withBody: true).FirstOrDefaultAsync();
        return row is null ? null : ToArticleDto(row);
    }

    private sealed record ArticleRow(
        string Slug, string Title, string Description, string? Body, List<string> Tags,
        DateTime CreatedAt, DateTime UpdatedAt, bool Favorited, int FavoritesCount,
        string Username, string? Bio, string? Image, bool Following);

    private static IQueryable<ArticleRow> Project(IQueryable<Article> query, AppDb db, int? viewerId, bool withBody) =>
        query.Select(a => new ArticleRow(
            a.Slug, a.Title, a.Description,
            withBody ? a.Body : null,
            a.Tags.OrderBy(t => t.Position).Select(t => t.Name).ToList(),
            a.CreatedAt, a.UpdatedAt,
            viewerId != null && a.Favorites.Any(f => f.UserId == viewerId),
            a.Favorites.Count,
            a.Author.Username, a.Author.Bio, a.Author.Image,
            viewerId != null && db.Follows.Any(f => f.FollowerId == viewerId && f.FolloweeId == a.AuthorId)));

    private static ArticleDto ToArticleDto(ArticleRow r) =>
        new(r.Slug, r.Title, r.Description, r.Body, r.Tags,
            Api.Timestamp(r.CreatedAt), Api.Timestamp(r.UpdatedAt), r.Favorited, r.FavoritesCount,
            new ProfileDto(r.Username, r.Bio, r.Image, r.Following));

    private static int ParseNonNegative(string? value, int fallback) =>
        int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out var n) ? n : fallback;

    private static async Task<bool> TrySave(AppDb db)
    {
        try
        {
            await db.SaveChangesAsync();
            return true;
        }
        catch (DbUpdateException)
        {
            db.ChangeTracker.Clear();
            return false;
        }
    }

    private static async Task<IResult> DuplicateUserError(AppDb db, string username, string email, int selfId)
    {
        if (await db.Users.AnyAsync(u => u.Username == username && u.Id != selfId))
            return Api.Error(409, "username", "has already been taken");
        return Api.Error(409, "email", "has already been taken");
    }

    /// <summary>
    /// Derives the slug from the title. If it is taken, a random suffix is appended so that
    /// duplicate titles still get distinct slugs; the unique index guards against races.
    /// </summary>
    private static async Task SaveWithUniqueSlug(AppDb db, Article article)
    {
        var baseSlug = Slugify(article.Title);
        for (var attempt = 0; ; attempt++)
        {
            var candidate = attempt == 0 ? baseSlug : $"{baseSlug}-{Guid.NewGuid().ToString("N")[..8]}";
            if (candidate != article.Slug && await db.Articles.AnyAsync(a => a.Slug == candidate && a.Id != article.Id))
                continue;
            article.Slug = candidate;
            try
            {
                await db.SaveChangesAsync();
                return;
            }
            catch (DbUpdateException) when (attempt < 5)
            {
                // Lost a race on the unique slug index; retry with a new suffix.
            }
        }
    }

    private static string Slugify(string title)
    {
        var normalized = title.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(normalized.Length);
        foreach (var c in normalized)
            if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(c);
        var slug = NonAlphanumeric().Replace(sb.ToString().ToLowerInvariant(), "-").Trim('-');
        return slug.Length == 0 ? "article" : slug;
    }

    [GeneratedRegex("[^a-z0-9]+")]
    private static partial Regex NonAlphanumeric();
}
