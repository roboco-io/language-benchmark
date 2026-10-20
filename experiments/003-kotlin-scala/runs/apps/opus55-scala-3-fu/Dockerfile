# ---- Build stage: compile and stage the production (release) distribution ----
FROM sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_1.13.0_3.3.8 AS build
WORKDIR /build

# Resolve dependencies first so this layer is cached across source changes.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt ./project/
RUN sbt -batch update

COPY app ./app
COPY conf ./conf
RUN sbt -batch stage

# ---- Runtime stage: JRE only ----
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --create-home --uid 10001 realworld \
 && mkdir -p /app/data && chown realworld /app/data

COPY --from=build --chown=realworld /build/target/universal/stage /app

USER realworld

# The schema is created automatically on startup when the database is empty.
ENV PORT=8000 \
    DATABASE_PATH=/app/data/realworld.db \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 --enable-native-access=ALL-UNNAMED"
VOLUME /app/data
EXPOSE 8000

ENTRYPOINT ["/bin/sh", "-c", "exec /app/bin/realworld-play -Dhttp.port=\"$PORT\" -Dpidfile.path=/dev/null"]
