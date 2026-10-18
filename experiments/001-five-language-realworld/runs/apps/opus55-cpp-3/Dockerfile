# syntax=docker/dockerfile:1

FROM ubuntu:24.04 AS build
ARG DROGON_VERSION=v1.9.14
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential ca-certificates cmake git \
      libjsoncpp-dev libssl-dev libsqlite3-dev uuid-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch ${DROGON_VERSION} --recurse-submodules \
      https://github.com/drogonframework/drogon.git /tmp/drogon \
    && cmake -S /tmp/drogon -B /tmp/drogon/build -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_EXAMPLES=OFF -DBUILD_CTL=OFF -DBUILD_POSTGRESQL=OFF \
      -DBUILD_MYSQL=OFF -DBUILD_REDIS=OFF -DBUILD_SQLITE=ON -DBUILD_SHARED_LIBS=OFF \
    && cmake --build /tmp/drogon/build -j"$(nproc)" \
    && cmake --install /tmp/drogon/build \
    && rm -rf /tmp/drogon

WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -j"$(nproc)" \
    && strip build/realworld

FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      libjsoncpp25 libssl3t64 libsqlite3-0 libuuid1 zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data
COPY --from=build /src/build/realworld /usr/local/bin/realworld
ENV PORT=8000 \
    DATABASE_PATH=/data/realworld.db
VOLUME ["/data"]
EXPOSE 8000
CMD ["/usr/local/bin/realworld"]
