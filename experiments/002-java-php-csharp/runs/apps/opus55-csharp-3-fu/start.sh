#!/usr/bin/env bash
# Installs dependencies, builds a release build, and runs the server in the foreground.
# The server listens on $PORT (default 8000).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$ROOT/src/Conduit/Conduit.csproj"
OUT="$ROOT/out"

export PORT="${PORT:-8000}"
export DATABASE_PATH="${DATABASE_PATH:-$ROOT/data/conduit.db}"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1

dotnet restore "$PROJECT"
dotnet publish "$PROJECT" -c Release --no-restore -o "$OUT"

exec dotnet "$OUT/Conduit.dll"
