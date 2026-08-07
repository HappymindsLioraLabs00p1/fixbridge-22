# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Run as a non-root user
RUN useradd -r -u 1001 appuser
COPY --from=build /app/target/fixbridge-backend-*.jar app.jar
USER appuser
EXPOSE 8080

# Fit inside a small container (512 MB free tiers). Left at defaults the JVM sizes its heap from the
# HOST's memory, overshoots the container limit and gets OOM-killed, which shows up as a crash loop.
#   MaxRAMPercentage  — heap as a share of the CONTAINER limit, leaving room for metaspace/threads
#   SerialGC          — lower footprint than G1 on a single small instance
#   TieredStopAtLevel — quicker startup, which matters when the platform health-checks early
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
