using System.Text;
using Conduit;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

var port = Environment.GetEnvironmentVariable("PORT") is { Length: > 0 } p ? p : "8000";
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

var dbPath = Environment.GetEnvironmentVariable("DATABASE_PATH") is { Length: > 0 } d ? d : "conduit.db";
var dbDir = Path.GetDirectoryName(Path.GetFullPath(dbPath));
if (dbDir is not null) Directory.CreateDirectory(dbDir);
var connectionString = new SqliteConnectionStringBuilder
{
    DataSource = dbPath,
    Cache = SqliteCacheMode.Private,
    Pooling = true,
    DefaultTimeout = 5,
}.ToString();

var jwtSecret = Environment.GetEnvironmentVariable("JWT_SECRET");
if (string.IsNullOrEmpty(jwtSecret))
{
    jwtSecret = "conduit-development-secret-change-me-in-production";
    Console.WriteLine("warning: JWT_SECRET is not set; using an insecure development secret");
}

builder.Logging.SetMinimumLevel(LogLevel.Warning);
builder.Logging.AddFilter("Microsoft.Hosting.Lifetime", LogLevel.Information);

builder.Services.AddSingleton(new JwtService(Encoding.UTF8.GetBytes(jwtSecret)));
builder.Services.AddDbContextPool<AppDb>(o => o
    .UseSqlite(connectionString)
    .AddInterceptors(new SqlitePragmaInterceptor()));

var app = builder.Build();

// Create the schema on an empty database and switch the file to WAL mode (persistent per database file).
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDb>();
    db.Database.EnsureCreated();
    db.Database.ExecuteSqlRaw("PRAGMA journal_mode = WAL;");
    var journalMode = db.Database.SqlQueryRaw<string>("SELECT journal_mode AS Value FROM pragma_journal_mode").AsEnumerable().First();
    var busyTimeout = db.Database.SqlQueryRaw<int>("SELECT timeout AS Value FROM pragma_busy_timeout").AsEnumerable().First();
    Console.WriteLine($"SQLite journal_mode={journalMode} busy_timeout={busyTimeout}ms");
}

// Kestrel serves requests on the default .NET thread pool, which scales with the CPU core count.
ThreadPool.GetMinThreads(out var minWorkers, out var minIo);
ThreadPool.SetMinThreads(Math.Max(minWorkers, Environment.ProcessorCount), Math.Max(minIo, Environment.ProcessorCount));
Console.WriteLine($"Conduit listening on port {port} with {Environment.ProcessorCount} CPU cores, database {Path.GetFullPath(dbPath)}");

// Return RealWorld-style JSON for unhandled exceptions instead of an empty 500.
app.Use(async (ctx, next) =>
{
    try
    {
        await next(ctx);
    }
    catch (Exception e) when (!ctx.Response.HasStarted)
    {
        app.Logger.LogError(e, "Unhandled exception");
        ctx.Response.Clear();
        await Errors.Of(500, "server", "internal error").ExecuteAsync(ctx);
    }
});

UserEndpoints.Map(app);
ArticleEndpoints.Map(app);

app.MapFallback((HttpContext ctx) => Errors.Of(404, "route", "not found"));

app.Run();
