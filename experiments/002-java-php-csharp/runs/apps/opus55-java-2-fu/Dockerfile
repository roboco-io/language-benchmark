# ---- Build stage: compile an optimized release jar ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Runtime stage: slim JRE only ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/app.jar /app/app.jar
RUN mkdir -p /app/data
ENV PORT=8080 \
    DB_PATH=/app/data/realworld.db
EXPOSE 8080
VOLUME ["/app/data"]
# Hibernate creates the schema automatically when the database is empty.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
