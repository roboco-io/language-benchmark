# Release build of the RealWorld backend (Drogon + SQLite).
FROM ubuntu:24.04 AS build
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential cmake git pkg-config ca-certificates \
      libssl-dev libsqlite3-dev libjsoncpp-dev uuid-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
COPY scripts/install-drogon-linux.sh /tmp/install-drogon-linux.sh
RUN /tmp/install-drogon-linux.sh
WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -j"$(nproc)"

FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      libssl3t64 libsqlite3-0 libjsoncpp25 libuuid1 zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data
COPY --from=build /usr/local/lib/ /usr/local/lib/
RUN ldconfig
COPY --from=build /src/build/realworld /usr/local/bin/realworld
ENV PORT=8000 \
    DB_PATH=/data/realworld.db
EXPOSE 8000
VOLUME /data
CMD ["realworld"]
