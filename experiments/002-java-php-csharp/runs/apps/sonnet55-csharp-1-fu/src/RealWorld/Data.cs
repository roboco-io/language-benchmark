using System.Data.Common;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;

namespace RealWorld;

public class User
{
    public int Id { get; set; }
    public string Username { get; set; } = "";
    public string Email { get; set; } = "";
    public string PasswordHash { get; set; } = "";
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
    public string Slug { get; set; } = "";
    public string Title { get; set; } = "";
    public string Description { get; set; } = "";
    public string Body { get; set; } = "";
    public int AuthorId { get; set; }
    public User Author { get; set; } = null!;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public List<ArticleTag> Tags { get; set; } = new();
}

public class ArticleTag
{
    public int ArticleId { get; set; }
    public string Tag { get; set; } = "";
}

public class Favorite
{
    public int UserId { get; set; }
    public int ArticleId { get; set; }
}

public class Comment
{
    public int Id { get; set; }
    public int ArticleId { get; set; }
    public int AuthorId { get; set; }
    public User Author { get; set; } = null!;
    public string Body { get; set; } = "";
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
        b.Entity<User>().HasIndex(u => u.Username).IsUnique();
        b.Entity<User>().HasIndex(u => u.Email).IsUnique();
        b.Entity<Follow>().HasKey(f => new { f.FollowerId, f.FolloweeId });
        b.Entity<Article>().HasIndex(a => a.Slug).IsUnique();
        b.Entity<Article>().HasIndex(a => a.CreatedAt);
        b.Entity<Article>().HasMany(a => a.Tags).WithOne().HasForeignKey(t => t.ArticleId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<ArticleTag>().HasKey(t => new { t.ArticleId, t.Tag });
        b.Entity<Favorite>().HasKey(f => new { f.UserId, f.ArticleId });
        b.Entity<Comment>().HasIndex(c => c.ArticleId);
        b.Entity<Comment>().HasOne<Article>().WithMany().HasForeignKey(c => c.ArticleId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<Favorite>().HasOne<Article>().WithMany().HasForeignKey(f => f.ArticleId).OnDelete(DeleteBehavior.Cascade);
    }
}

/// <summary>Applies per-connection pragmas: WAL journal and a 5000 ms busy timeout.</summary>
public class SqlitePragmaInterceptor : DbConnectionInterceptor
{
    public override void ConnectionOpened(DbConnection connection, ConnectionEndEventData eventData)
    {
        using var cmd = connection.CreateCommand();
        cmd.CommandText = "PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;";
        cmd.ExecuteNonQuery();
    }

    public override async Task ConnectionOpenedAsync(DbConnection connection, ConnectionEndEventData eventData, CancellationToken ct = default)
    {
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = "PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;";
        await cmd.ExecuteNonQueryAsync(ct);
    }
}
