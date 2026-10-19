#!/usr/bin/env bash
# Restores dependencies, builds (Release), and runs the server in the foreground.
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-8000}"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1
dotnet restore src/Api/Api.csproj
dotnet build src/Api/Api.csproj -c Release --no-restore -o out
exec dotnet out/RealWorld.Api.dll
