# Build stage: compile Drogon and the server in Release mode.
FROM ubuntu:24.04 AS build
ARG DROGON_VERSION=v1.9.14
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates git cmake make g++ \
      libjsoncpp-dev uuid-dev zlib1g-dev libssl-dev libsqlite3-dev \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch ${DROGON_VERSION} --recurse-submodules \
      https://github.com/drogonframework/drogon.git /tmp/drogon \
    && cmake -S /tmp/drogon -B /tmp/drogon/build -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_EXAMPLES=OFF -DBUILD_CTL=OFF -DBUILD_ORM=ON -DBUILD_POSTGRESQL=OFF \
      -DBUILD_MYSQL=OFF -DBUILD_SQLITE=ON -DBUILD_REDIS=OFF -DBUILD_TESTING=OFF \
    && cmake --build /tmp/drogon/build -j"$(nproc)" \
    && cmake --install /tmp/drogon/build \
    && rm -rf /tmp/drogon
WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -j"$(nproc)" \
    && strip build/realworld

# Runtime stage: only the shared libraries the binary needs.
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
      libjsoncpp25 libuuid1 zlib1g libssl3t64 libsqlite3-0 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/build/realworld /usr/local/bin/realworld
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8080
CMD ["/usr/local/bin/realworld"]
