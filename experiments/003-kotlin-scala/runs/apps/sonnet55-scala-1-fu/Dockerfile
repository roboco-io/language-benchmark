# ---- build stage: release (staged) distribution ----
FROM eclipse-temurin:25-jdk AS build
ARG SBT_VERSION=1.11.7
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /opt \
    && rm -rf /var/lib/apt/lists/*
ENV PATH="/opt/sbt/bin:${PATH}"
WORKDIR /src
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt .
RUN sbt -batch update
COPY app app
COPY conf conf
RUN sbt -batch stage

# ---- runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/universal/stage /app
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
ENV PORT=8000 \
    DATABASE_URL="jdbc:sqlite:/data/realworld.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=true&synchronous=NORMAL"
VOLUME /data
EXPOSE 8000
ENTRYPOINT ["/app/docker-entrypoint.sh"]
