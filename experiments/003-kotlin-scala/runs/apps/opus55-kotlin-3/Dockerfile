# Build stage: compile an optimized executable jar.
FROM gradle:9.8-jdk25-noble AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN gradle --no-daemon -q bootJar \
 && java -Djarmode=tools -jar build/libs/realworld-1.0.0.jar extract --layers --launcher --destination /app

# Runtime stage: JRE only.
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN useradd --system --uid 1001 app && mkdir -p /data && chown app /data
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER app
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
