using Microsoft.EntityFrameworkCore;

namespace RealWorld.Api;

public class User
{
    public int Id { get; set; }
    public string Username { get; set; } = "";
    public string Email { get; set; } = "";
    public string PasswordHash { get; set; } = "";
    public string? Bio { get; set; }
    public string? Image { get; set; }
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
}

public class ArticleTag
{
    public int Id { get; set; }
    public int ArticleId { get; set; }
    public string Name { get; set; } = "";
}

public class Favorite
{
    public int UserId { get; set; }
    public int ArticleId { get; set; }
}

public class Follow
{
    public int FollowerId { get; set; }
    public int FolloweeId { get; set; }
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
    public DbSet<Article> Articles => Set<Article>();
    public DbSet<ArticleTag> ArticleTags => Set<ArticleTag>();
    public DbSet<Favorite> Favorites => Set<Favorite>();
    public DbSet<Follow> Follows => Set<Follow>();
    public DbSet<Comment> Comments => Set<Comment>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<User>().HasIndex(u => u.Username).IsUnique();
        b.Entity<User>().HasIndex(u => u.Email).IsUnique();
        b.Entity<Article>().HasIndex(a => a.Slug).IsUnique();
        b.Entity<Article>().HasIndex(a => a.AuthorId);
        b.Entity<Article>().HasMany(a => a.Tags).WithOne().HasForeignKey(t => t.ArticleId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<ArticleTag>().HasIndex(t => t.Name);
        b.Entity<ArticleTag>().HasIndex(t => t.ArticleId);
        b.Entity<Favorite>().HasKey(f => new { f.UserId, f.ArticleId });
        b.Entity<Favorite>().HasIndex(f => f.ArticleId);
        b.Entity<Follow>().HasKey(f => new { f.FollowerId, f.FolloweeId });
        b.Entity<Comment>().HasOne(c => c.Author).WithMany().HasForeignKey(c => c.AuthorId).OnDelete(DeleteBehavior.Cascade);
        b.Entity<Comment>().HasIndex(c => c.ArticleId);
    }
}
