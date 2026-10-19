using System.Data.Common;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using RealWorld;

var builder = WebApplication.CreateBuilder(args);

var port = Environment.GetEnvironmentVariable("PORT") ?? "8080";
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
builder.Logging.ClearProviders();
builder.Logging.AddConsole();
builder.Logging.SetMinimumLevel(LogLevel.Warning);

var dbPath = Environment.GetEnvironmentVariable("DB_PATH") ?? "data/realworld.db";
var dbDir = Path.GetDirectoryName(Path.GetFullPath(dbPath));
if (dbDir != null) Directory.CreateDirectory(dbDir);

var secret = Environment.GetEnvironmentVariable("JWT_SECRET") ?? "realworld-dev-secret-change-me-in-production-0123456789";
builder.Services.AddSingleton(new JwtService(System.Text.Encoding.UTF8.GetBytes(secret)));
builder.Services.AddSingleton<SqlitePragmaInterceptor>();
builder.Services.AddDbContextPool<AppDb>((sp, o) => o
    .UseSqlite($"Data Source={dbPath};Default Timeout=5;Pooling=True")
    .AddInterceptors(sp.GetRequiredService<SqlitePragmaInterceptor>()));

var app = builder.Build();

using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDb>();
    db.Database.EnsureCreated();
}

app.Use(async (ctx, next) =>
{
    try { await next(); }
    catch (BadHttpRequestException) { await Api.Errors(ctx, 422, "body", "is invalid"); }
    catch (JsonException) { await Api.Errors(ctx, 422, "body", "is invalid"); }
});

Api.Map(app);
app.Run();

namespace RealWorld
{
    /// <summary>Applies WAL mode and busy_timeout on every new SQLite connection.</summary>
    public class SqlitePragmaInterceptor : DbConnectionInterceptor
    {
        public override void ConnectionOpened(DbConnection connection, ConnectionEndEventData eventData)
        {
            using var cmd = connection.CreateCommand();
            cmd.CommandText = "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;";
            cmd.ExecuteNonQuery();
        }

        public override async Task ConnectionOpenedAsync(DbConnection connection, ConnectionEndEventData eventData, CancellationToken ct = default)
        {
            await using var cmd = connection.CreateCommand();
            cmd.CommandText = "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;";
            await cmd.ExecuteNonQueryAsync(ct);
        }
    }
}
