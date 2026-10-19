# Build stage: release build of the Spring Boot fat jar.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime stage.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
ENV PORT=8080 \
    DB_PATH=/data/realworld.db
RUN mkdir -p /data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
