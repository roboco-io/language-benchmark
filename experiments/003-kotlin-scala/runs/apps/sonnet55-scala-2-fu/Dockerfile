# syntax=docker/dockerfile:1

# ---- Build stage: compile and stage a release (production) distribution ----
FROM eclipse-temurin:25-jdk AS build
ARG SBT_VERSION=1.10.7
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /opt
ENV PATH="/opt/sbt/bin:${PATH}"
WORKDIR /src
# Resolve dependencies first so that this layer is cached when only sources change.
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt .
RUN sbt -batch update
COPY app app
COPY conf conf
RUN sbt -batch stage

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /opt/app
COPY --from=build /src/target/universal/stage /opt/app
# The SQLite file lives in /data; the schema is created on startup when the file is empty.
RUN mkdir -p /data
ENV PORT=8000 \
    DB_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8000
# Play reads the port from PORT (see conf/application.conf); thread pools follow the visible CPU count.
ENTRYPOINT ["/opt/app/bin/realworld-play", "-Dpidfile.path=/dev/null"]
