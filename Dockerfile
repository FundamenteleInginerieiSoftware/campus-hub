# One image with the whole app (ADR-002): Spring Boot serves the API and the React build.
# Build from the repo root:  docker build -t campus-hub .
# Three stages: only the last one ends up in the image, so Node, the JDK and Gradle stay out of it.

# Stage 1: build the React app into static files (frontend/dist).
# Debian "slim", not Alpine: Vite's native build tools ship for glibc, Alpine uses musl.
FROM node:24-slim AS frontend
WORKDIR /app
# package files first: Docker reuses the npm ci layer until the dependencies change.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: build the Spring Boot jar, with the React files inside it.
FROM eclipse-temurin:25-jdk AS backend
WORKDIR /app
# Build files first: Gradle and the libraries are downloaded once and cached in this layer.
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
COPY backend/gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null
COPY backend/src src
# Anything in src/main/resources/static is served by Spring at "/", e.g. dist/index.html -> /index.html.
COPY --from=frontend /app/dist src/main/resources/static
# No tests here: CI already ran them on this commit, and they need a database.
RUN ./gradlew bootJar --no-daemon && mv build/libs/*.jar app.jar

# Stage 3: what runs on EC2. Only a Java runtime (no compiler) and the jar.
FROM eclipse-temurin:25-jre-alpine
# Bug fixed: the base image can be weeks behind Alpine's security fixes. The first image pushed to
# ECR (9 Nov) had zlib 1.3.2 with a critical CVE (CVE-2026-4738) although Alpine already shipped
# 1.4. Fix: install the OS updates at build time, so every build gets the patched packages.
# Not root: if someone breaks into the app, they can't change the container's system files.
RUN apk upgrade --no-cache && addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=backend /app/app.jar app.jar
# prod = settings from environment variables (application-prod.yml), filled on EC2 from SSM (ADR-003).
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# Docker marks the container "unhealthy" if this fails 3 times; deploy.yml waits for "healthy".
# start-period: Flyway and Spring need some seconds to start before the first check counts.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1
# MaxRAMPercentage: the JVM sizes its heap from the container's memory limit instead of
# the whole server's RAM (t3.small = 2 GB).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
