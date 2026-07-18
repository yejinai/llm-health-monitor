# ── Build stage ──────────────────────────────────────────────
FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle installDist --no-daemon

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
