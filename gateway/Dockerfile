# syntax=docker/dockerfile:1

# ---------- Stage 1: build the fat jar with Maven ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies in their own layer: re-downloaded only when pom.xml changes,
# not on every source edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI; skipping them here keeps image builds fast and deterministic.
RUN mvn -B -q package -DskipTests

# ---------- Stage 2: slim runtime image (JRE only, no Maven, no sources) ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as an unprivileged user.
RUN addgroup -S gatepulse && adduser -S gatepulse -G gatepulse
COPY --from=build /build/target/gatepulse.jar /app/gatepulse.jar
USER gatepulse

# PORT is read by the application (Render injects its own value at runtime).
ENV PORT=8080 \
    MODE=embedded \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/health" || exit 1

# exec so the JVM is PID 1 and receives SIGTERM directly -> graceful shutdown hook runs.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/gatepulse.jar"]
