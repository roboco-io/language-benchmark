# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Resolve dependencies in a separate layer so source changes do not refetch them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination /extracted

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --home /app app \
 && mkdir -p /data && chown app:app /data
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 8080
# The schema is created automatically on first start with an empty database.
ENTRYPOINT ["sh", "-c", "exec java --enable-native-access=ALL-UNNAMED $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
