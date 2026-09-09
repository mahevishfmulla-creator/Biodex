# Stage 1: Build the Application JAR
FROM gradle:8.10-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle buildFatJar --no-daemon

# Stage 2: Minimal Runtime Environment
FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/*-all.jar /app/biodex-backend.jar
ENTRYPOINT ["java", "-jar", "/app/biodex-backend.jar"]