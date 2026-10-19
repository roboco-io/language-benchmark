using Microsoft.EntityFrameworkCore;
using RealWorld.Data;
using RealWorld.Infrastructure;

namespace RealWorld.Endpoints;

public static class CommentEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapGet("/api/articles/{slug}/comments", List);
        app.MapPost("/api/articles/{slug}/comments", Create);
        app.MapPut("/api/articles/{slug}/comments/{id}", Update);
        app.MapDelete("/api/articles/{slug}/comments/{id}", Delete);
    }

    private static async Task<int> ArticleId(AppDb db, string slug) =>
        await db.Articles.Where(a => a.Slug == slug).Select(a => (int?)a.Id).FirstOrDefaultAsync()
        ?? throw ApiException.NotFound("article");

    private static IQueryable<CommentDtoRow> Project(AppDb db, IQueryable<Comment> q, int? me) =>
        q.Select(c => new CommentDtoRow(c.Id, c.CreatedAt, c.UpdatedAt, c.Body,
            c.Author.Username, c.Author.Bio, c.Author.Image,
            me != null && db.Follows.Any(f => f.FollowerId == me && f.FolloweeId == c.AuthorId)));

    private sealed record CommentDtoRow(int Id, DateTime CreatedAt, DateTime UpdatedAt, string Body,
        string AuthorUsername, string? AuthorBio, string? AuthorImage, bool Following);

    private static CommentDto ToDto(CommentDtoRow r) => new(r.Id, Api.FormatDate(r.CreatedAt), Api.FormatDate(r.UpdatedAt), r.Body,
        new ProfileDto(r.AuthorUsername, r.AuthorBio, r.AuthorImage, r.Following));

    private static async Task<IResult> List(string slug, HttpContext ctx, AppDb db)
    {
        var me = Api.OptionalUser(ctx);
        var articleId = await ArticleId(db, slug);
        var rows = await Project(db, db.Comments.AsNoTracking().Where(c => c.ArticleId == articleId).OrderBy(c => c.Id), me)
            .ToListAsync();
        return Results.Ok(new { comments = rows.Select(ToDto) });
    }

    private static async Task<IResult> Create(string slug, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var articleId = await ArticleId(db, slug);
        var body = await Api.ReadRoot(ctx, "comment");
        var v = new Validation();
        var text = Api.RequiredString(body, "body", v);
        v.ThrowIfAny();

        var now = Api.Now();
        var comment = new Comment { Body = text!, ArticleId = articleId, AuthorId = me, CreatedAt = now, UpdatedAt = now };
        db.Comments.Add(comment);
        await db.SaveChangesAsync();
        var row = await Project(db, db.Comments.AsNoTracking().Where(c => c.Id == comment.Id), me).FirstAsync();
        return Results.Json(new { comment = ToDto(row) }, statusCode: 201);
    }

    private static async Task<IResult> Update(string slug, string id, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var articleId = await ArticleId(db, slug);
        if (!int.TryParse(id, out var commentId)) throw ApiException.NotFound("comment");
        var comment = await db.Comments.FirstOrDefaultAsync(c => c.Id == commentId && c.ArticleId == articleId)
                      ?? throw ApiException.NotFound("comment");
        if (comment.AuthorId != me) throw ApiException.Forbidden("comment");

        var body = await Api.ReadRoot(ctx, "comment");
        var v = new Validation();
        var text = Api.RequiredString(body, "body", v);
        v.ThrowIfAny();

        comment.Body = text!;
        comment.UpdatedAt = Api.Now();
        await db.SaveChangesAsync();
        var row = await Project(db, db.Comments.AsNoTracking().Where(c => c.Id == comment.Id), me).FirstAsync();
        return Results.Ok(new { comment = ToDto(row) });
    }

    private static async Task<IResult> Delete(string slug, string id, HttpContext ctx, AppDb db)
    {
        var me = await Api.RequiredUser(ctx, db);
        var articleId = await ArticleId(db, slug);
        if (!int.TryParse(id, out var commentId)) throw ApiException.NotFound("comment");
        var authorId = await db.Comments.Where(c => c.Id == commentId && c.ArticleId == articleId)
                           .Select(c => (int?)c.AuthorId).FirstOrDefaultAsync()
                       ?? throw ApiException.NotFound("comment");
        if (authorId != me) throw ApiException.Forbidden("comment");
        await db.Comments.Where(c => c.Id == commentId).ExecuteDeleteAsync();
        return Results.NoContent();
    }
}
