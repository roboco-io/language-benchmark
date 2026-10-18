# ---- build stage: optimized (Release) build ----
FROM ubuntu:24.04 AS build
WORKDIR /src
COPY scripts/install-deps.sh scripts/install-deps.sh
RUN ./scripts/install-deps.sh
COPY CMakeLists.txt .
COPY src src
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
 && cmake --build build -j"$(nproc)" \
 && strip build/realworld

# ---- runtime stage ----
FROM ubuntu:24.04
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libjsoncpp25 libssl3t64 libsqlite3-0 libc-ares2 libbrotli1 zlib1g libuuid1 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/build/realworld /usr/local/bin/realworld
ENV PORT=8080 \
    DB_PATH=/data/realworld.db
RUN mkdir -p /data
VOLUME /data
EXPOSE 8080
# The schema is created automatically when the database file is empty or missing.
CMD ["realworld"]
