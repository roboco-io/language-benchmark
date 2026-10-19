#!/usr/bin/env bash
# Restores dependencies, builds a Release build and runs the server in the foreground.
# The server listens on $PORT (default 8080). The SQLite file defaults to ./data/realworld.db.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$ROOT/src/RealWorld/RealWorld.csproj"
OUT="$ROOT/src/RealWorld/bin/Release/net10.0"

export PORT="${PORT:-8080}"
export DATABASE_PATH="${DATABASE_PATH:-$ROOT/data/realworld.db}"

dotnet restore "$PROJECT"
dotnet build "$PROJECT" -c Release --no-restore

exec dotnet "$OUT/RealWorld.dll"
