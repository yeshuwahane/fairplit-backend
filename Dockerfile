# ==============================================================================
# FairSplit Backend - Production Multi-Stage Dockerfile
# ==============================================================================

# Stage 1: Build fat JAR
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Gradle wrapper and build configuration
COPY gradle/ gradle/
COPY gradlew gradlew
COPY gradle.properties gradle.properties
COPY build.gradle.kts build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts

RUN chmod +x ./gradlew

# Pre-fetch Gradle dependencies
RUN ./gradlew --no-daemon dependencies || true

# Copy backend source code and build fat JAR
COPY src/ src/
RUN ./gradlew --no-daemon buildFatJar

# Stage 2: Production JRE runtime
FROM eclipse-temurin:21-jre

WORKDIR /app

# Create non-root application user and persistent data directories
RUN groupadd -r fairsplit && useradd -r -g fairsplit fairsplit && \
    mkdir -p /app/data /app/uploads && \
    chown -R fairsplit:fairsplit /app

# Copy deterministic standalone fat JAR from build stage
COPY --from=build --chown=fairsplit:fairsplit /app/build/libs/fairsplit-backend.jar /app/app.jar

USER fairsplit

# Environment Defaults (can be overridden by cloud platform / container runtime)
ENV PORT=8080
ENV HOST=0.0.0.0
ENV APP_ENV=prod

EXPOSE 8080

# Health check configuration
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD java -version || exit 1

# Launch Ktor EngineMain via standalone fat JAR with production-grade JVM settings
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
