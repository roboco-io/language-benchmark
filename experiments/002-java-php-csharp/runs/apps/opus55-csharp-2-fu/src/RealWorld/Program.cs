using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using RealWorld.Data;
using RealWorld.Endpoints;
using RealWorld.Infrastructure;

var builder = WebApplication.CreateBuilder(args);

// Listen on all interfaces using the PORT environment variable (default 8080).
var port = Environment.GetEnvironmentVariable("PORT");
if (string.IsNullOrWhiteSpace(port)) port = "8080";
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

// Kestrel runs on the default .NET thread pool, which sizes itself to the number of CPU cores.
var dbPath = Environment.GetEnvironmentVariable("DATABASE_PATH");
if (string.IsNullOrWhiteSpace(dbPath)) dbPath = Path.Combine(AppContext.BaseDirectory, "data", "realworld.db");
Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(dbPath))!);
var connectionString = new SqliteConnectionStringBuilder
{
    DataSource = dbPath,
    Cache = SqliteCacheMode.Private,
    Pooling = true,
    DefaultTimeout = 5,
}.ToString();

builder.Services.AddDbContextPool<AppDb>(o => o
    .UseSqlite(connectionString)
    .AddInterceptors(new SqlitePragmaInterceptor())
    .UseQueryTrackingBehavior(QueryTrackingBehavior.TrackAll));

var secret = Environment.GetEnvironmentVariable("JWT_SECRET");
if (string.IsNullOrWhiteSpace(secret))
{
    secret = "realworld-development-secret-change-me";
    Console.WriteLine("warning: JWT_SECRET is not set; using an insecure development secret.");
}
builder.Services.AddSingleton(new Jwt(secret));

var app = builder.Build();

// Create the schema on an empty database and switch the journal to WAL (persistent per database file).
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDb>();
    db.Database.EnsureCreated();
    db.Database.ExecuteSqlRaw("PRAGMA journal_mode = WAL;");
    var journal = db.Database.SqlQueryRaw<string>("PRAGMA journal_mode").AsEnumerable().First();
    var busy = db.Database.SqlQueryRaw<int>("PRAGMA busy_timeout").AsEnumerable().First();
    app.Logger.LogInformation("SQLite {Path}: journal_mode={Journal}, busy_timeout={Busy}ms, workers={Cores} cores",
        dbPath, journal, busy, Environment.ProcessorCount);
}

app.Use(async (ctx, next) =>
{
    try
    {
        await next(ctx);
    }
    catch (ApiException e)
    {
        await Api.Error(e.Status, e.Errors).ExecuteAsync(ctx);
    }
    catch (BadHttpRequestException e)
    {
        await Api.Error(e.StatusCode, new() { ["body"] = [e.Message] }).ExecuteAsync(ctx);
    }
});

UserEndpoints.Map(app);
ArticleEndpoints.Map(app);
CommentEndpoints.Map(app);

app.Run();
