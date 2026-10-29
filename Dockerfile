# syntax=docker/dockerfile:1

# ── Stage 1: build the React SPA ──────────────────────────────────────────────
# Both build stages run on the BUILD platform (--platform=$BUILDPLATFORM) because they produce
# platform-independent artifacts: web/dist (static assets) and the installDist JARs. A multi-arch
# build (linux/amd64 + linux/arm64) therefore compiles each once, natively; the runtime stage is
# the only per-platform one, and its `RUN useradd` is the only instruction that is ever emulated.
FROM --platform=$BUILDPLATFORM node:26-alpine@sha256:40cd011b55415bc894e45b02fdd5a5dfde056921b7d23a64c74f80497af48797 AS web
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
# separator), not a floating "21-jdk" tag that can drift between pulls. The runtime-stage JRE
# below must stay on the same 21.0.12 update (infrastructure.test.js), but may run a respin ahead.
# Every FROM is also pinned by multi-arch index digest (tag@sha256:...): the tag stays readable
# and Dependabot's docker ecosystem bumps tag and digest together.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.12_8-jdk@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d AS server
WORKDIR /src
# Copy build scripts + wrapper first so the Gradle distribution download caches.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew --version --no-daemon
# Module build files + the dependency lockfiles (locking is ON — a missing lockfile fails the
# resolution, which is the point), then sources.
COPY gradle.lockfile settings-gradle.lockfile buildscript-gradle.lockfile ./
COPY core/build.gradle.kts core/gradle.lockfile core/buildscript-gradle.lockfile core/
COPY server/build.gradle.kts server/gradle.lockfile server/buildscript-gradle.lockfile server/
COPY core/src/ core/src/
COPY server/src/ server/src/
# installDist keeps every dependency as its own JAR, so Flyway's ServiceLoader
# plugin discovery works exactly as under `:server:run` (a fat JAR collapses the
# duplicate META-INF/services descriptors and breaks Flyway at startup).
RUN ./gradlew :server:installDist --no-daemon

# ── Stage 3: runtime ──────────────────────────────────────────────────────────
# Same 21.0.12 update as the build stage (mise.toml and jvmToolchain(21)), tag-and-digest-pinned.
# The runtime runs a respin ahead: 21.0.12.1_1 is a distinct JVM build (Temurin 21.0.12.1+1) whose
# base image also carries the OS-package fixes the image scan demands (21.0.12_8-jre has none), and
# mise.toml/CI do not name 21.0.12.1 yet; the JDK, mise and CI follow with BACKLOG.md's "Align
# Temurin with the 21.0.12.1 respin". When the scan goes red again, move to the newest clean base for
# this update (a later respin tag), never a runtime-only jump to a later update, never an ignore file.
FROM eclipse-temurin:21.0.12.1_1-jre@sha256:f37956012e0cb88400e5b3689aeae2d2d735364c1922ae2225536163b5a99f56 AS runtime
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
# glibc malloc otherwise keeps up to 8 x cores arenas, and each of the ~100 JVM threads pins its own
# 2 MiB; two arenas bound that native memory. Measurements: .claude/docs/security.md "Entity read
# memory budget" (Heap layout).
ENV MALLOC_ARENA_MAX=2
# The build identity the telemetry sampler records beside the process (`telemetry_processes.build_version`/`build_commit`,
# observability.md "Telemetry"). Empty by default (a plain `docker build` or `docker compose up --build` leaves them unset and the
# columns null); pass `--build-arg TOADIE_BUILD_VERSION=... --build-arg TOADIE_BUILD_COMMIT=...` to stamp them. Declared after
# every COPY and RUN of the stage on purpose: an ARG value change only invalidates the layers after it, never the COPY layers above.
ARG TOADIE_BUILD_VERSION=""
ARG TOADIE_BUILD_COMMIT=""
ENV TOADIE_BUILD_VERSION=$TOADIE_BUILD_VERSION \
    TOADIE_BUILD_COMMIT=$TOADIE_BUILD_COMMIT
EXPOSE 8081
# Plain `docker run` gets a health status too. docker-compose.yaml keeps its explicit copy of this
# check (development mode, no redirect); Kubernetes ignores HEALTHCHECK and uses the probes in
# k8s/app-deployment.yaml. The -jre base ships curl.
# The image runs production mode, where HttpsRedirect answers a plain-HTTP request with a 301 and
# `curl --fail` treats a 3xx as success, so a bare probe would stay green whatever the database
# does. X-Forwarded-Proto: https marks the probe as already secure, but the app honours it ONLY
# with HTTP_BEHIND_PROXY=true (plugins/Http.kt) - which any real deployment behind its TLS
# terminator sets. So the check demands exactly 200: without that setting the 301 makes the
# container unhealthy (it cannot serve a browser either), and with it /readyz's DB round-trip
# decides (503 while PostgreSQL is away).
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 CMD test "$(curl --silent --max-time 2 --output /dev/null --write-out '%{http_code}' --header 'X-Forwarded-Proto: https' http://localhost:8081/readyz)" = 200 || exit 1
ENTRYPOINT ["/app/bin/server"]
