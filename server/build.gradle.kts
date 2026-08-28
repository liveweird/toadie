
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}


application {
    mainClass = "io.ktor.server.netty.EngineMain"
    // Footprint tuning for this small, I/O-bound, low-traffic service. Baked into the installDist
    // launcher (bin/server → the Docker image) and `:server:run`; `test` is unaffected. Measured
    // (in Lettuce, the same stack) on a 512 MiB Linux container: baseline G1 drifts ~345→410 MiB
    // RSS as it grows its heap; this config sits at a steady ~270 MiB — ~25% lower and
    // predictable. Startup is ~1.6 s either way; the win is memory, not startup.
    //   - UseSerialGC        : G1's concurrent threads + region metadata are pure overhead for a
    //                          small heap / few cores; SerialGC alone saved ~75 MiB here.
    //   - Xmx256m            : the app holds no large caches; 256 MiB is comfortable headroom for
    //                          light bursts (drop to 192m to trim ~25 MiB more if traffic stays low).
    //   - TieredStopAtLevel=1: C1-only JIT — trims code-cache + C2-compiler memory (~50 MiB here).
    //                          Peak CPU-bound throughput is lower, which is irrelevant for an
    //                          I/O-bound tool; REMOVE this flag if the service ever runs hot.
    // Override per-deployment with the JAVA_OPTS / SERVER_OPTS env vars (the launcher appends both).
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseSerialGC",
        "-Xmx256m",
        "-XX:TieredStopAtLevel=1",
    )
}

kotlin {
    jvmToolchain(21)
}

kover {
    reports {
        verify {
            rule {
                // Line-coverage floor (actual ~96.7%, 2026-08-27 labels-feature re-measure).
                minBound(95)
                // Branch-coverage floor (actual ~73.6%, 2026-08-27 labels-feature re-measure; the gap to 100% is dominated by
                // kotlinx-serialization synthetic branches in @Serializable data classes). NOTE:
                // `check` runs only koverVerify — run `:server:koverXmlReport` for fresh actuals.
                minBound(70, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}

// Static analysis (plain rule sets only — no type resolution). Rule tuning lives in
// config/detekt/detekt.yml; the task rides `check`, so `build` gates on it.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
dependencies {
    implementation(project(":core"))
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.cachingHeaders)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.csrf)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.hsts)
    implementation(ktorLibs.server.httpRedirect)
    implementation(ktorLibs.server.metrics)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.rateLimit)
    implementation(ktorLibs.server.resources)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.swagger)
    implementation(libs.angus.mail)
    implementation(libs.bcrypt)
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.logback.classic)
    implementation(libs.opentelemetry.logbackAppender)
    implementation(libs.postgresql)
    implementation(libs.r2dbc.postgresql)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.client.contentNegotiation)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.swagger.request.validator.core)
    testImplementation(libs.testcontainers.postgresql)
}

// Every test-client interaction with /api/ is validated against the OpenAPI spec (see
// OpenApiConformance.kt). `-Dopenapi.conformance=warn|off` relaxes it for drift triage.
tasks.withType<Test> {
    systemProperty("openapi.conformance", System.getProperty("openapi.conformance", "fail"))
}
