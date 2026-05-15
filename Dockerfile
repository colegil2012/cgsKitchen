# syntax=docker/dockerfile:1.7

# ---- Build stage -------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

# Build the fat jar; skip tests for faster deploys (CI runs them separately).
RUN mvn -B -ntp clean package -DskipTests \
 && cp target/*.jar app.jar

# ---- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run as a non-root user
RUN groupadd --system spring && useradd --system --gid spring --home /app spring \
 && chown -R spring:spring /app

COPY --from=build --chown=spring:spring /app/app.jar app.jar

USER spring:spring

EXPOSE 8080

# Container-aware JVM settings:
#   - MaxRAMPercentage lets the JVM scale heap to the container memory limit
#   - G1GC is the sane default for server workloads on Java 21
#   - urandom keeps Spring Boot/Tomcat startup fast in containers
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]