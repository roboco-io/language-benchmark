using Conduit;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

// Kestrel listens on $PORT (default 8000) on all interfaces. Request processing runs on the
// default .NET thread pool, which scales with the number of available CPU cores.
var port = int.TryParse(Environment.GetEnvironmentVariable("PORT"), out var p) ? p : 8000;
builder.WebHost.ConfigureKestrel(o => o.ListenAnyIP(port));

var dbPath = Environment.GetEnvironmentVariable("DATABASE_PATH") ?? Path.Combine(AppContext.BaseDirectory, "conduit.db");
var dbDir = Path.GetDirectoryName(Path.GetFullPath(dbPath));
if (!string.IsNullOrEmpty(dbDir)) Directory.CreateDirectory(dbDir);
var connectionString = new SqliteConnectionStringBuilder
{
    DataSource = dbPath,
    Cache = SqliteCacheMode.Private,
    DefaultTimeout = 5,
}.ToString();

builder.Services.AddDbContextPool<AppDb>(o => o
    .UseSqlite(connectionString)
    .AddInterceptors(new SqlitePragmaInterceptor()));

var secret = Environment.GetEnvironmentVariable("JWT_SECRET") ?? "conduit-development-secret-change-me";
builder.Services.AddSingleton(new TokenService(secret));

builder.Logging.AddFilter("Microsoft.EntityFrameworkCore", LogLevel.Warning);

var app = builder.Build();

// Create the schema on an empty database and switch to WAL journaling (persistent per database file).
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDb>();
    db.Database.EnsureCreated();
    db.Database.ExecuteSqlRaw("PRAGMA journal_mode = WAL;");
}

// Map validation failures and unexpected errors to RealWorld-style JSON error bodies.
app.Use(async (context, next) =>
{
    try
    {
        await next(context);
    }
    catch (ValidationException ex) when (!context.Response.HasStarted)
    {
        await Api.Error(422, ex.Field, ex.Message).ExecuteAsync(context);
    }
    catch (BadHttpRequestException) when (!context.Response.HasStarted)
    {
        await Api.Error(422, "body", "is invalid").ExecuteAsync(context);
    }
    catch (Exception ex) when (!context.Response.HasStarted)
    {
        app.Logger.LogError(ex, "Unhandled exception");
        await Api.Error(500, "server", "internal error").ExecuteAsync(context);
    }
});

Endpoints.Map(app);

app.Logger.LogInformation("Listening on port {Port} with {Cores} CPU cores; database {Db}", port, Environment.ProcessorCount, dbPath);
app.Run();
