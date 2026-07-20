# ── Build stage ──────────────────────────────────────────────
# Uses the project's own checksum-pinned Gradle wrapper for reproducibility
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew gradle.properties build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew installDist --no-daemon

# ── Runtime stage ────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/install/llm-health-monitor ./
COPY config.example.json ./config.json

# Mount your own config and persist data:
#   docker run -v ./config.json:/app/config.json -v ./data:/app/data \
#     -e OPENAI_API_KEY=... -p 8080:8080 llm-health-monitor
EXPOSE 8080
ENTRYPOINT ["./bin/llm-health-monitor"]
