FROM gradle:jdk25 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/app.jar app.jar
ENV PORT=8080 DB_PATH=/data/realworld.db
RUN mkdir -p /data
EXPOSE 8080
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseContainerSupport", "-jar", "app.jar"]
