# syntax=docker/dockerfile:1

# ── Stage 1: build the React SPA ──────────────────────────────────────────────
FROM node:26-alpine@sha256:143494b1da2945f061539253adc65e4f1569ddf07da2d384c022c791a9d90a4a AS web
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
# Pinned to the mise.toml patch (temurin-21.0.12+8.0.LTS → the Docker Hub tag's underscore
# separator) so the build-stage JDK and the runtime-stage JRE below are provably the same
# Java build, not just "21-jdk"/"21-jre" floating tags that can drift apart between pulls.
# Every FROM is also pinned by multi-arch index digest (tag@sha256:...): the tag stays readable
# and Dependabot's docker ecosystem bumps tag and digest together.
FROM eclipse-temurin:21.0.12_8-jdk@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d AS server
WORKDIR /src
# Copy build scripts + wrapper first so the Gradle distribution download caches.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew --version --no-daemon
# Module build files + the dependency lockfiles (locking is ON — a missing lockfile fails the
# resolution, which is the point), then sources.
COPY settings-gradle.lockfile buildscript-gradle.lockfile ./
COPY core/build.gradle.kts core/gradle.lockfile core/
COPY server/build.gradle.kts server/gradle.lockfile server/
COPY core/src/ core/src/
COPY server/src/ server/src/
# installDist keeps every dependency as its own JAR, so Flyway's ServiceLoader
# plugin discovery works exactly as under `:server:run` (a fat JAR collapses the
# duplicate META-INF/services descriptors and breaks Flyway at startup).
RUN ./gradlew :server:installDist --no-daemon

# ── Stage 3: runtime ──────────────────────────────────────────────────────────
# The same pinned JDK/JRE build as the build stage (mise.toml and jvmToolchain(21)): one Java
# version everywhere, tag-and-digest-pinned rather than trusting "21-jre" to keep meaning the same bytes.
FROM eclipse-temurin:21.0.12_8-jre@sha256:49e21e16e3c86eb7816a44a67549910ed090fbeb40c29c525d58bf5e02e91b0f AS runtime
# Non-root runtime user: the base image's default user is root, and the application never
# needs to bind a privileged port (8081) or write outside its own install directory. Created
# BEFORE the COPYs so `--chown` sets ownership in the copy layers themselves — a trailing
# `chown -R` would duplicate the whole install into one more layer under overlay2.
RUN useradd --uid 10001 --user-group --home-dir /app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --chown=app:app --from=server /src/server/build/install/server/ ./
COPY --chown=app:app --from=web /web/dist web
USER app
ENV WEB_STATIC_DIR=/app/web
# The shipped image runs in production mode: the JWT-secret and seed-password fail-closed
# checks are active, and HSTS + HTTPS redirect are on. Local demos (docker-compose.yaml)
# explicitly override this back to true.
ENV KTOR_DEVELOPMENT=false
# No outbound email unless the deployment opts in: a real deployment sets
# MAIL_TRANSPORT=smtp with real SMTP_* settings (production mode refuses `log`).
ENV MAIL_TRANSPORT=disabled
EXPOSE 8081
# Plain `docker run` gets a health status too. docker-compose.yaml keeps its explicit copy of this
# check; Kubernetes ignores HEALTHCHECK and uses the probes in k8s/app-deployment.yaml. The -jre
# base ships curl.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 CMD curl --fail --silent --max-time 2 http://localhost:8081/readyz || exit 1
ENTRYPOINT ["/app/bin/server"]
