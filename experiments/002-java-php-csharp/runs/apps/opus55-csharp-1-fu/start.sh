#!/usr/bin/env bash
# Restores dependencies, builds a Release version of the API and runs it in the foreground.
# The server listens on $PORT (default 8000).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1
export PORT="${PORT:-8000}"
export DATABASE_PATH="${DATABASE_PATH:-$ROOT/data/conduit.db}"

dotnet restore src/Conduit/Conduit.csproj
dotnet publish src/Conduit/Conduit.csproj -c Release --no-restore -o "$ROOT/out"

exec dotnet "$ROOT/out/Conduit.dll"
