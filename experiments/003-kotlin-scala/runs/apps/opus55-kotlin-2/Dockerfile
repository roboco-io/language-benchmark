# Build stage: compile the optimized release jar.
FROM gradle:jdk25 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN gradle --no-daemon -q bootJar

# Runtime stage: JRE only.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/realworld.jar ./realworld.jar
ENV PORT=8080 \
    DB_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8080
# The schema is created automatically on first start with an empty database.
ENTRYPOINT ["java", "-XX:+UseParallelGC", "-XX:MaxRAMPercentage=75", "-jar", "/app/realworld.jar"]
