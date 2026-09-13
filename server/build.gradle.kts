
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
        filters {
            excludes {
                // The Port blueprint wire DTOs (blueprints/Blueprint.kt) are PURE data classes — every
                // rule lives in BlueprintValidation.kt/PropertyValidation.kt, every mapper in BlueprintKt.
                // Their ~65 optional fields make the compiler-generated default-args and kotlinx-serialization
                // constructors carry one synthetic branch per field, half of which no Kotlin call site can
                // reach (measured: three test strategies left PropertyDefinition at exactly 97/97). Excluding
                // the data classes (and their generated $serializer/$Companion) keeps the branch floor honest
                // about LOGIC; BlueprintWireNamesTest still pins their wire shape. Extend this list only with
                // another logic-free @Serializable DTO family, never with a class that carries a rule.
                classes(
                    "ch.nokillswit.blueprints.PropertyDefinition", "ch.nokillswit.blueprints.PropertyDefinition$*",
                    "ch.nokillswit.blueprints.ArrayItems", "ch.nokillswit.blueprints.ArrayItems$*",
                    "ch.nokillswit.blueprints.SpecAuthentication", "ch.nokillswit.blueprints.SpecAuthentication$*",
                    "ch.nokillswit.blueprints.BlueprintSchema", "ch.nokillswit.blueprints.BlueprintSchema$*",
                    "ch.nokillswit.blueprints.RelationDefinition", "ch.nokillswit.blueprints.RelationDefinition$*",
                    "ch.nokillswit.blueprints.MirrorPropertyDefinition", "ch.nokillswit.blueprints.MirrorPropertyDefinition$*",
                    "ch.nokillswit.blueprints.CalculationPropertyDefinition", "ch.nokillswit.blueprints.CalculationPropertyDefinition$*",
                    "ch.nokillswit.blueprints.AggregationCalculationSpec", "ch.nokillswit.blueprints.AggregationCalculationSpec$*",
                    "ch.nokillswit.blueprints.AggregationQuery", "ch.nokillswit.blueprints.AggregationQuery$*",
                    "ch.nokillswit.blueprints.AggregationPropertyDefinition", "ch.nokillswit.blueprints.AggregationPropertyDefinition$*",
                    "ch.nokillswit.blueprints.OwnershipDefinition", "ch.nokillswit.blueprints.OwnershipDefinition$*",
                    "ch.nokillswit.blueprints.BlueprintDefinition", "ch.nokillswit.blueprints.BlueprintDefinition$*",
                    "ch.nokillswit.blueprints.BlueprintRequest", "ch.nokillswit.blueprints.BlueprintRequest$*",
                    "ch.nokillswit.blueprints.BlueprintResponse", "ch.nokillswit.blueprints.BlueprintResponse$*",
                    "ch.nokillswit.blueprints.BlueprintList", "ch.nokillswit.blueprints.BlueprintList$*",
                    // The same idiom for entities/Entity.kt (Port migration phase 2, v1.24.0) — the wire
                    // DTOs are logic-free @Serializable data classes; every rule lives in EntityValidation.kt.
                    "ch.nokillswit.entities.EntityRequest", "ch.nokillswit.entities.EntityRequest$*",
                    "ch.nokillswit.entities.EntityDocument", "ch.nokillswit.entities.EntityDocument$*",
                    "ch.nokillswit.entities.EntityFinding", "ch.nokillswit.entities.EntityFinding$*",
                    "ch.nokillswit.entities.EntityResponse", "ch.nokillswit.entities.EntityResponse$*",
                    // The two findings-bearing RFC 7807 bodies (v1.31.0, infra/validation/
                    // InvalidPayloadException.kt): ProblemDetail's five members plus `findings`, pure
                    // data — the exception classes that build them and the ErrorHandling.kt handler stay
                    // measured; only the DTOs' synthetic optional-field constructors are excluded.
                    "ch.nokillswit.entities.EntityInvalidProblem", "ch.nokillswit.entities.EntityInvalidProblem$*",
                    "ch.nokillswit.catalog.CatalogFileInvalidProblem", "ch.nokillswit.catalog.CatalogFileInvalidProblem$*",
                    // Phase 6 (v1.28.0, ontology import — blueprints/BlueprintImport.kt +
                    // entities/EntityImport.kt): the six request/row/response DTOs, the SAME
                    // logic-free family — every rule lives in the pure planBlueprintImport/
                    // planEntityImport functions and their tests, never in these classes.
                    "ch.nokillswit.blueprints.BlueprintImportRequest", "ch.nokillswit.blueprints.BlueprintImportRequest$*",
                    "ch.nokillswit.blueprints.BlueprintImportRow", "ch.nokillswit.blueprints.BlueprintImportRow$*",
                    "ch.nokillswit.blueprints.BlueprintImportResponse", "ch.nokillswit.blueprints.BlueprintImportResponse$*",
                    "ch.nokillswit.entities.EntityImportRequest", "ch.nokillswit.entities.EntityImportRequest$*",
                    "ch.nokillswit.entities.EntityImportRow", "ch.nokillswit.entities.EntityImportRow$*",
                    "ch.nokillswit.entities.EntityImportResponse", "ch.nokillswit.entities.EntityImportResponse$*",
                    // Phase 7 (2.0.0, entity query bar — PR2): the entity-query wire DTOs, the SAME
                    // logic-free family — every rule lives in QueryParser.kt/QueryValidator.kt/
                    // QueryEvaluator.kt and EntityService.kt's orchestration, never in these classes.
                    "ch.nokillswit.entityquery.QueryDiagnostic", "ch.nokillswit.entityquery.QueryDiagnostic$*",
                    "ch.nokillswit.entityquery.EntityQueryProblem", "ch.nokillswit.entityquery.EntityQueryProblem$*",
                    "ch.nokillswit.entityquery.EntityQueryCheckRequest", "ch.nokillswit.entityquery.EntityQueryCheckRequest$*",
                    "ch.nokillswit.entityquery.EntityQueryCheckResponse", "ch.nokillswit.entityquery.EntityQueryCheckResponse$*",
                )
            }
        }
        verify {
            rule {
                // Line-coverage floor (actual 97.72% locally, 2026-09-12 checkup re-measure — the CI runner
                // historically lands within ~0.05pp; keep the margin below a full point in mind).
                minBound(97)
                // Branch-coverage floor (actual 78.48% locally, 2026-09-12 v1.31.0 re-measure, with the blueprint
                // DTO exclusion above — the margin has eroded to ~0.5pp since the 2026-09-08 ~79.3%; the remaining
                // gap to 100% is dominated by kotlinx-serialization synthetic branches in the other @Serializable
                // data classes. A red branch gate with no code change means re-measure, then either add the
                // missing rows or lower deliberately in its own commit). NOTE: `check` runs only koverVerify —
                // run `:server:koverXmlReport` for fresh actuals.
                minBound(78, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
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
    implementation(ktorLibs.server.bodyLimit)
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
    implementation(libs.jackson.jq)
    implementation(libs.logback.classic)
    implementation(libs.okhttp)
    implementation(libs.opentelemetry.logbackAppender)
    implementation(libs.postgresql)
    implementation(libs.r2dbc.postgresql)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.client.contentNegotiation)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.swagger.request.validator.core)
    testImplementation(libs.testcontainers.postgresql)
}

// Every test-client interaction with /api/ is validated against the OpenAPI spec (see
// OpenApiConformance.kt). `-Dopenapi.conformance=warn|off` relaxes it for drift triage.
tasks.withType<Test> {
    systemProperty("openapi.conformance", System.getProperty("openapi.conformance", "fail"))
}
