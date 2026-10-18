# syntax=docker/dockerfile:1

# ---- Build stage: compile Drogon and the server in Release mode ----
FROM debian:bookworm AS build

ARG DROGON_VERSION=v1.9.14

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake git ca-certificates pkg-config \
        libjsoncpp-dev uuid-dev zlib1g-dev libssl-dev libsqlite3-dev \
        libbrotli-dev libc-ares-dev \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch ${DROGON_VERSION} --recurse-submodules \
        https://github.com/drogonframework/drogon /tmp/drogon \
    && cmake -S /tmp/drogon -B /tmp/drogon/build \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 \
        -DBUILD_EXAMPLES=OFF -DBUILD_CTL=OFF -DBUILD_TESTING=OFF \
        -DBUILD_ORM=ON -DBUILD_SQLITE=ON -DBUILD_POSTGRESQL=OFF \
        -DBUILD_MYSQL=OFF -DBUILD_REDIS=OFF -DBUILD_SHARED_LIBS=OFF \
    && cmake --build /tmp/drogon/build -j "$(nproc)" \
    && cmake --install /tmp/drogon/build \
    && rm -rf /tmp/drogon

WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -j "$(nproc)" \
    && strip build/realworld

# ---- Runtime stage: only the binary and its shared libraries ----
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        libjsoncpp25 libuuid1 zlib1g libssl3 libsqlite3-0 \
        libbrotli1 libc-ares2 ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /app app \
    && mkdir -p /data && chown app:app /data

COPY --from=build /src/build/realworld /app/realworld

USER app
WORKDIR /app
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db
EXPOSE 8080
VOLUME ["/data"]
CMD ["/app/realworld"]
