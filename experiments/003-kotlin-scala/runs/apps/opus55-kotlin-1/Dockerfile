# Build stage: produce the optimized Spring Boot executable jar.
FROM gradle:9-jdk25 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle dependencies --no-daemon -q > /dev/null
COPY src ./src
RUN gradle bootJar --no-daemon -q

# Runtime stage: JRE only.
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /data
COPY --from=build /src/build/libs/app.jar /app/app.jar
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8080
# The schema is created on startup when the database is empty.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseParallelGC", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
