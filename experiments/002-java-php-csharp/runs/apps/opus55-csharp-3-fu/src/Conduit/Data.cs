using System.Data.Common;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;

namespace Conduit;

public class User
{
    public int Id { get; set; }
    public string Username { get; set; } = "";
    public string Email { get; set; } = "";
    public string PasswordHash { get; set; } = "";
    public string? Bio { get; set; }
    public string? Image { get; set; }
    public List<Follow> Followers { get; set; } = new();
}

public class Follow
{
    public int FollowerId { get; set; }
    public int FolloweeId { get; set; }
}

public class Article
{
    public int Id { get; set; }
    public string Slug { get; set; } = "";
    public string Title { get; set; } = "";
    public string Description { get; set; } = "";
    public string Body { get; set; } = "";
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public int AuthorId { get; set; }
    public User Author { get; set; } = null!;
    public List<ArticleTag> Tags { get; set; } = new();
    public List<Favorite> Favorites { get; set; } = new();
}

public class Tag
{
    public int Id { get; set; }
    public string Name { get; set; } = "";
}

public class ArticleTag
{
    public int ArticleId { get; set; }
    public int TagId { get; set; }
    public Tag Tag { get; set; } = null!;
    // Keeps the tag order that the client supplied.
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
    public string Body { get; set; } = "";
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public int ArticleId { get; set; }
    public int AuthorId { get; set; }
    public User Author { get; set; } = null!;
}

public class AppDb(DbContextOptions<AppDb> options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    public DbSet<Follow> Follows => Set<Follow>();
    public DbSet<Article> Articles => Set<Article>();
    public DbSet<Tag> Tags => Set<Tag>();
    public DbSet<ArticleTag> ArticleTags => Set<ArticleTag>();
    public DbSet<Favorite> Favorites => Set<Favorite>();
    public DbSet<Comment> Comments => Set<Comment>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<User>().HasIndex(u => u.Username).IsUnique();
        b.Entity<User>().HasIndex(u => u.Email).IsUnique();

        b.Entity<Follow>().HasKey(f => new { f.FollowerId, f.FolloweeId });
        b.Entity<Follow>().HasOne<User>().WithMany().HasForeignKey(f => f.FollowerId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<Follow>().HasOne<User>().WithMany(u => u.Followers).HasForeignKey(f => f.FolloweeId).OnDelete(DeleteBehavior.Cascade);

        b.Entity<Article>().HasIndex(a => a.Slug).IsUnique();
        b.Entity<Article>().HasIndex(a => a.CreatedAt);
        b.Entity<Article>().HasOne(a => a.Author).WithMany().HasForeignKey(a => a.AuthorId).OnDelete(DeleteBehavior.Cascade);

        b.Entity<Tag>().HasIndex(t => t.Name).IsUnique();

        b.Entity<ArticleTag>().HasKey(t => new { t.ArticleId, t.TagId });
        b.Entity<ArticleTag>().HasIndex(t => t.TagId);
        b.Entity<ArticleTag>().HasOne<Article>().WithMany(a => a.Tags).HasForeignKey(t => t.ArticleId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<ArticleTag>().HasOne(t => t.Tag).WithMany().HasForeignKey(t => t.TagId).OnDelete(DeleteBehavior.Cascade);

        b.Entity<Favorite>().HasKey(f => new { f.UserId, f.ArticleId });
        b.Entity<Favorite>().HasIndex(f => f.ArticleId);
        b.Entity<Favorite>().HasOne<User>().WithMany().HasForeignKey(f => f.UserId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<Favorite>().HasOne<Article>().WithMany(a => a.Favorites).HasForeignKey(f => f.ArticleId).OnDelete(DeleteBehavior.Cascade);

        b.Entity<Comment>().HasIndex(c => c.ArticleId);
        b.Entity<Comment>().HasOne<Article>().WithMany().HasForeignKey(c => c.ArticleId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<Comment>().HasOne(c => c.Author).WithMany().HasForeignKey(c => c.AuthorId).OnDelete(DeleteBehavior.Cascade);
    }
}

/// <summary>
/// Applies per-connection SQLite settings every time a pooled connection is opened.
/// </summary>
public class SqlitePragmaInterceptor : DbConnectionInterceptor
{
    private const string Pragmas = "PRAGMA busy_timeout = 5000; PRAGMA foreign_keys = ON; PRAGMA synchronous = NORMAL;";

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
