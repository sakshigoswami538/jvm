# ── Stage 1: Build ──────────────────────────────────────────────────────────
# Downloads all Maven dependencies first (layer is cached until pom.xml changes).
# Source is copied separately so code changes don't bust the dependency layer.
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
# JRE-only image keeps the final image ~200 MB smaller than the JDK image.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# curl is needed for the Docker health check defined in docker-compose.yml
RUN apk add --no-cache curl

# Run as non-root for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

COPY --from=build /app/target/demo-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# MaxRAMPercentage: use at most 75% of the container's memory limit for the heap.
# UseContainerSupport is ON by default in Java 21 — the JVM reads cgroup limits.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
