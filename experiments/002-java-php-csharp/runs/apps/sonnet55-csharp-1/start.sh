#!/usr/bin/env bash
# Restores dependencies, builds a Release build, and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8000}"
export DB_PATH="${DB_PATH:-$PWD/realworld.db}"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1

dotnet restore src/RealWorld/RealWorld.csproj
dotnet build src/RealWorld/RealWorld.csproj -c Release --no-restore -o out
exec dotnet out/RealWorld.dll
