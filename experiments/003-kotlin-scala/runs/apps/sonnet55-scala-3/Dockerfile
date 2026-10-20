# syntax=docker/dockerfile:1

# ---- Build stage: compile and stage an optimized (non-debug) distribution ----
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
 && curl -fsSL "https://repo.scala-sbt.org/scalasbt/debian/sbt-1.12.15.deb" -o /tmp/sbt.deb \
 && apt-get install -y --no-install-recommends /tmp/sbt.deb \
 && rm -rf /var/lib/apt/lists/* /tmp/sbt.deb
WORKDIR /src
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt .
RUN sbt -batch update
COPY app app
COPY conf conf
RUN sbt -batch stage

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /opt/realworld
COPY --from=build /src/target/universal/stage ./stage
COPY scripts/run-server.sh ./run-server.sh
ENV APP_BIN=/opt/realworld/stage/bin/realworld \
    PORT=8000 \
    DB_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8000
ENTRYPOINT ["/opt/realworld/run-server.sh"]
