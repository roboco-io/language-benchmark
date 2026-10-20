# Build stage: optimized release jar
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL https://services.gradle.org/distributions/gradle-9.8.2-bin.zip -o /tmp/g.zip \
 && unzip -q /tmp/g.zip -d /opt && rm /tmp/g.zip
ENV PATH="/opt/gradle-9.8.2/bin:${PATH}"
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon -q

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/app.jar app.jar
RUN mkdir -p /data
ENV PORT=8080 DB_PATH=/data/realworld.db
EXPOSE 8080
# The schema is created on startup when the database is empty.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
