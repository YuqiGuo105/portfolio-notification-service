# syntax=docker/dockerfile:1.6
# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Non-root user
RUN groupadd -r app && useradd -r -g app app
USER app

COPY --from=build /workspace/target/portfolio-notification-service.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -Dserver.port=${PORT} -jar /app/app.jar"]
