FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY src/Conduit/Conduit.csproj src/Conduit/
RUN dotnet restore src/Conduit/Conduit.csproj
COPY src/ src/
RUN dotnet publish src/Conduit/Conduit.csproj -c Release --no-restore -o /app

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app .
ENV PORT=8000 \
    DATABASE_PATH=/app/data/conduit.db \
    ASPNETCORE_HTTP_PORTS= \
    DOTNET_TieredPGO=1
RUN mkdir -p /app/data && chown -R app:app /app/data
USER app
VOLUME ["/app/data"]
EXPOSE 8000
ENTRYPOINT ["dotnet", "Conduit.dll"]
