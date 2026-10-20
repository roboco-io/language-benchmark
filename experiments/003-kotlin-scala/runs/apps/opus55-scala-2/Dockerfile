# ---- Build stage: compile and stage an optimized production release ----
FROM eclipse-temurin:25-jdk AS build

ARG SBT_VERSION=1.11.7
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
    | tar -xz -C /opt \
 && rm -rf /var/lib/apt/lists/*
ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /src
# Resolve dependencies first so this layer is cached across source changes.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt project/
RUN sbt -batch update

COPY app app
COPY conf conf
RUN sbt -batch stage

# ---- Runtime stage: JRE plus the staged release only ----
FROM eclipse-temurin:25-jre

RUN useradd --system --create-home --uid 10001 realworld \
 && mkdir -p /data && chown realworld /data
COPY --from=build --chown=realworld /src/target/universal/stage /app

USER realworld
WORKDIR /app
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db
EXPOSE 8080
VOLUME ["/data"]

# The schema is created on startup when the database is empty.
ENTRYPOINT ["/app/bin/realworld-play"]
