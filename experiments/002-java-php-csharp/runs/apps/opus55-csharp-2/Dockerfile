# syntax=docker/dockerfile:1
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY src/RealWorld/RealWorld.csproj src/RealWorld/
RUN dotnet restore src/RealWorld/RealWorld.csproj
COPY src/ src/
RUN dotnet publish src/RealWorld/RealWorld.csproj -c Release --no-restore -o /app

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app ./
# The schema is created automatically on startup when the database is empty.
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db
RUN mkdir -p /data && chown -R app:app /data
VOLUME ["/data"]
USER app
EXPOSE 8080
ENTRYPOINT ["dotnet", "RealWorld.dll"]
