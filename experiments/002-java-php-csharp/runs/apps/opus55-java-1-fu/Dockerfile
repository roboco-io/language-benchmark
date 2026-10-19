# syntax=docker/dockerfile:1

# ---- build stage: release build of the executable jar ------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination /out

# ---- runtime stage ------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app && mkdir -p /app/data && chown app /app/data
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER app

# The server listens on $PORT. The schema is created automatically on an empty database.
ENV PORT=8080 \
    DB_PATH=/app/data/realworld.db \
    JAVA_TOOL_OPTIONS="-XX:+UseParallelGC -XX:MaxRAMPercentage=75"
EXPOSE 8080
VOLUME ["/app/data"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
