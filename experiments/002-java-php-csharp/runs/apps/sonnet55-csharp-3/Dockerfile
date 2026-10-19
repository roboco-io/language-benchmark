FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY src/Api/Api.csproj src/Api/
RUN dotnet restore src/Api/Api.csproj
COPY src/Api/ src/Api/
RUN dotnet publish src/Api/Api.csproj -c Release --no-restore -o /app

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app .
ENV PORT=8000 \
    DATABASE_PATH=/data/realworld.db \
    DOTNET_gcServer=0
RUN mkdir -p /data
EXPOSE 8000
ENTRYPOINT ["dotnet", "RealWorld.Api.dll"]
