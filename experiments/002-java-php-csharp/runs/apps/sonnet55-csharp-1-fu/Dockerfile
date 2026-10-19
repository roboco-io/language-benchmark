FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY src/RealWorld/RealWorld.csproj RealWorld/
RUN dotnet restore RealWorld/RealWorld.csproj
COPY src/RealWorld/ RealWorld/
RUN dotnet publish RealWorld/RealWorld.csproj -c Release --no-restore -o /app

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app .
ENV PORT=8000 \
    DB_PATH=/data/realworld.db \
    DOTNET_gcServer=0
RUN mkdir -p /data
EXPOSE 8000
ENTRYPOINT ["dotnet", "RealWorld.dll"]
