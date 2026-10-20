# syntax=docker/dockerfile:1

# ---- Build stage: compile and stage the release distribution with sbt ----
FROM eclipse-temurin:25-jdk AS build
ARG SBT_VERSION=1.11.7
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /opt \
 && rm -rf /var/lib/apt/lists/*
ENV PATH="/opt/sbt/bin:${PATH}"
WORKDIR /src

# Resolve dependencies first so that they are cached independently of source changes.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt ./project/
RUN sbt -batch update

COPY app ./app
COPY conf ./conf
RUN sbt -batch stage

# ---- Runtime stage: JRE plus the staged application ----
FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --home-dir /app realworld
WORKDIR /app
COPY --from=build --chown=realworld:realworld /src/target/universal/stage /app
RUN mkdir -p /app/data && chown realworld:realworld /app/data
USER realworld

ENV PORT=8000 \
    DATABASE_PATH=/app/data/realworld.db
EXPOSE 8000
VOLUME ["/app/data"]

ENTRYPOINT ["/app/bin/realworld"]
