# syntax=docker/dockerfile:1
FROM debian:sid AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake pkg-config libdrogon-dev libsqlite3-dev libssl-dev \
        libjsoncpp-dev uuid-dev zlib1g-dev libpq-dev libmariadb-dev libhiredis-dev \
        libbrotli-dev libyaml-cpp-dev libc-ares-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --parallel "$(nproc)"

FROM debian:sid-slim
RUN apt-get update && apt-get install -y --no-install-recommends libdrogon1t64 libsqlite3-0 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/realworld /app/realworld
# The app creates the schema on startup if the database file is empty or missing.
ENV PORT=8080 DATABASE_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8080
CMD ["/app/realworld"]
