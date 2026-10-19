# Stage 1: release build
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Stage 2: slim runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/realworld.jar app.jar
ENV PORT=8080 \
    DB_PATH=/data/realworld.db
RUN mkdir -p /data
VOLUME /data
EXPOSE 8080
# Schema is created by Hibernate on startup when the database is empty.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-jar", "app.jar"]
