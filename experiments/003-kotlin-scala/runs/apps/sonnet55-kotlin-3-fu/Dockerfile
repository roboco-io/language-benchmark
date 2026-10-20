FROM gradle:jdk25 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon -q bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/app.jar app.jar
ENV PORT=8080 DB_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
