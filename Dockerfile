# syntax=docker/dockerfile:1

# ── Stage 1: build the React SPA ──────────────────────────────────────────────
FROM node:24-alpine AS web
RUN apk add --no-cache git
WORKDIR /web
# Install deps first for layer caching. --legacy-peer-deps per web/ README
# (openapi-typescript declares TS ^5 while the scaffold uses TS 6).
COPY web/package.json web/package-lock.json ./
RUN npm ci --legacy-peer-deps
COPY web/ ./
# .git is copied last so a new commit only busts the build layer, and the version
# stamp is computed explicitly here: the vite config's `git status` dirty check
# would always be a false positive in this stage (the worktree is just web/).
COPY .git .git
# schema.ts is committed, so `vite build` needs no running server / gen:api.
RUN GIT_SHA=$(git rev-parse --short HEAD) \
    GIT_COMMIT_TIME=$(git log -1 --format=%cI) \
    npm run build

# ── Stage 2: build the server distribution ────────────────────────────────────
FROM eclipse-temurin:21-jdk AS server
WORKDIR /src
# Copy build scripts + wrapper first so the Gradle distribution download caches.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew --version --no-daemon
# Module build files, then sources.
COPY core/build.gradle.kts core/
COPY server/build.gradle.kts server/
COPY core/src/ core/src/
COPY server/src/ server/src/
# installDist keeps every dependency as its own JAR, so Flyway's ServiceLoader
# plugin discovery works exactly as under `:server:run` (a fat JAR collapses the
# duplicate META-INF/services descriptors and breaks Flyway at startup).
RUN ./gradlew :server:installDist --no-daemon

# ── Stage 3: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=server /src/server/build/install/server/ ./
COPY --from=web /web/dist web
ENV WEB_STATIC_DIR=/app/web
# The shipped image runs in production mode: the JWT-secret and seed-password fail-closed
# checks are active, and HSTS + HTTPS redirect are on. Local demos (docker-compose.yaml)
# explicitly override this back to true.
ENV KTOR_DEVELOPMENT=false
# No outbound email unless the deployment opts in: a real deployment sets
# MAIL_TRANSPORT=smtp with real SMTP_* settings (production mode refuses `log`).
ENV MAIL_TRANSPORT=disabled
EXPOSE 8081
ENTRYPOINT ["/app/bin/server"]
