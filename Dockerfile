# syntax=docker/dockerfile:1
# Local-dev image: runs the pre-built Spring Boot jar.
# Build the jar first from the repo root:  mvn -pl realtime-gateway -am -DskipTests install
FROM amazoncorretto:25
WORKDIR /app
COPY target/*.jar /app/app.jar
EXPOSE 8095
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
