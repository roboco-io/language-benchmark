# Build stage: release build of the Spring Boot jar (tests skipped; the Hurl suite runs against a live server).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/realworld.jar app.jar
ENV PORT=8080 \
    DB_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8080
# The schema is created by Hibernate on startup when the database file is empty or missing.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
