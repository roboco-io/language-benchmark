using System.Data.Common;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;

namespace RealWorld.Data;

public class User
{
    public int Id { get; set; }
    public required string Username { get; set; }
    public required string Email { get; set; }
    public required string PasswordHash { get; set; }
    public string? Bio { get; set; }
    public string? Image { get; set; }
}

public class Follow
{
    public int FollowerId { get; set; }
    public int FolloweeId { get; set; }
}

public class Article
{
    public int Id { get; set; }
    public required string Slug { get; set; }
    public required string Title { get; set; }
    public required string Description { get; set; }
    public required string Body { get; set; }
    public int AuthorId { get; set; }
    public User Author { get; set; } = null!;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public List<ArticleTag> Tags { get; set; } = [];
    public List<Favorite> Favorites { get; set; } = [];
}

public class ArticleTag
{
    public int ArticleId { get; set; }
    public required string Name { get; set; }
    // Preserves the order in which the client supplied the tags.
    public int Position { get; set; }
}

public class Favorite
{
    public int UserId { get; set; }
    public int ArticleId { get; set; }
}

public class Comment
{
    public int Id { get; set; }
    public required string Body { get; set; }
    public int ArticleId { get; set; }
    public int AuthorId { get; set; }
    public User Author { get; set; } = null!;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}

public class AppDb(DbContextOptions<AppDb> options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    public DbSet<Follow> Follows => Set<Follow>();
    public DbSet<Article> Articles => Set<Article>();
    public DbSet<ArticleTag> ArticleTags => Set<ArticleTag>();
    public DbSet<Favorite> Favorites => Set<Favorite>();
    public DbSet<Comment> Comments => Set<Comment>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<User>(e =>
        {
            e.HasIndex(u => u.Username).IsUnique();
            e.HasIndex(u => u.Email).IsUnique();
        });

        b.Entity<Follow>(e =>
        {
            e.HasKey(f => new { f.FollowerId, f.FolloweeId });
            e.HasIndex(f => f.FolloweeId);
            e.HasOne<User>().WithMany().HasForeignKey(f => f.FollowerId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne<User>().WithMany().HasForeignKey(f => f.FolloweeId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Article>(e =>
        {
            e.HasIndex(a => a.Slug).IsUnique();
            e.HasIndex(a => new { a.AuthorId, a.CreatedAt });
            e.HasIndex(a => a.CreatedAt);
            e.HasOne(a => a.Author).WithMany().HasForeignKey(a => a.AuthorId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<ArticleTag>(e =>
        {
            e.HasKey(t => new { t.ArticleId, t.Name });
            e.HasIndex(t => t.Name);
            e.HasOne<Article>().WithMany(a => a.Tags).HasForeignKey(t => t.ArticleId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Favorite>(e =>
        {
            e.HasKey(f => new { f.UserId, f.ArticleId });
            e.HasIndex(f => f.ArticleId);
            e.HasOne<User>().WithMany().HasForeignKey(f => f.UserId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne<Article>().WithMany(a => a.Favorites).HasForeignKey(f => f.ArticleId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Comment>(e =>
        {
            e.HasIndex(c => c.ArticleId);
            e.HasOne<Article>().WithMany().HasForeignKey(c => c.ArticleId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne(c => c.Author).WithMany().HasForeignKey(c => c.AuthorId).OnDelete(DeleteBehavior.Cascade);
        });
    }
}

/// <summary>
/// Applies per-connection SQLite pragmas. busy_timeout is a per-connection setting,
/// so it must be issued every time a pooled connection is opened.
/// </summary>
public sealed class SqlitePragmaInterceptor : DbConnectionInterceptor
{
    private const string Pragmas = "PRAGMA busy_timeout = 5000; PRAGMA synchronous = NORMAL; PRAGMA foreign_keys = ON;";

    public override void ConnectionOpened(DbConnection connection, ConnectionEndEventData eventData)
    {
        using var cmd = connection.CreateCommand();
        cmd.CommandText = Pragmas;
        cmd.ExecuteNonQuery();
    }

    public override async Task ConnectionOpenedAsync(DbConnection connection, ConnectionEndEventData eventData, CancellationToken cancellationToken = default)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = Pragmas;
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }
}
