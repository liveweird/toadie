// Plugin classpath locking - see the root build.gradle.kts and `.claude/docs/dependency-reproducibility.md`.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies {
        constraints {
            // The Ktor Gradle plugin 3.6.0 constrains commons-lang3 to the open range [3.18.0,).
            // Hold it at 3.21.0, the version whose checksum is recorded in the verification
            // metadata, so a new upstream release cannot enter a cold resolution (Docker, a fresh
            // CI cache) and fail strict verification; move it deliberately.
            classpath("org.apache.commons:commons-lang3") {
                version { strictly("3.21.0") }
            }
            // Advisory floors for the same plugin's Jib/Shadow integrations (build-time only):
            // jackson 2.22.3 and plexus-utils 4.1.0. Literals, not the catalog: the buildscript
            // block is evaluated before `libs` exists. Keep jackson in step with the `jackson-bom`
            // catalog line. Floor policy: `.claude/docs/dependency-reproducibility.md`.
            classpath("com.fasterxml.jackson.core:jackson-databind:2.22.4")
            classpath("com.fasterxml.jackson.core:jackson-core:2.22.4")
            classpath("com.fasterxml.jackson.core:jackson-annotations:2.22")
            classpath("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.4")
            classpath("org.codehaus.plexus:plexus-utils:4.1.0")
            // log4j 2.25.1 carries MEDIUM advisories (below the scan's gate), cleared anyway so
            // the report starts at zero.
            classpath("org.apache.logging.log4j:log4j-core:2.26.1")
            classpath("org.apache.logging.log4j:log4j-api:2.26.1")
        }
    }
}

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
    //                          `entities/EntityReadBudget.kt`'s ENTITY_READ_BUDGET_BYTES (64 MiB) is
    //                          sized off THIS value — change the two together (`.claude/docs/security.md`
    //                          "Entity read memory budget").
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
                    "ch.nokillswit.blueprints.BlueprintTiers", "ch.nokillswit.blueprints.BlueprintTiers$*",
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
                    // QueryEvaluator.kt and EntityGraphRead.kt's orchestration, never in these classes.
                    "ch.nokillswit.entityquery.QueryDiagnostic", "ch.nokillswit.entityquery.QueryDiagnostic$*",
                    "ch.nokillswit.entityquery.EntityQueryProblem", "ch.nokillswit.entityquery.EntityQueryProblem$*",
                    "ch.nokillswit.entityquery.EntityQueryCheckRequest", "ch.nokillswit.entityquery.EntityQueryCheckRequest$*",
                    "ch.nokillswit.entityquery.EntityQueryCheckResponse", "ch.nokillswit.entityquery.EntityQueryCheckResponse$*",
                )
            }
        }
        verify {
            rule {
                // Line-coverage floor (actual 97.46%, 2026-10-06 checkup re-measure — the CI runner
                // historically lands within ~0.05pp; keep the margin below a full point in mind).
                minBound(97)
                // Branch-coverage floor (actual 78.56%, 2026-10-06 checkup re-measure, with the blueprint
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

// GQL-CON-003 compares the working SDL with an immutable Git baseline. The implementation
// lives in the test source set so this build-time gate adds nothing to the application runtime.
val checkGraphqlCompatibility = tasks.register<JavaExec>("checkGraphqlCompatibility") {
    group = "verification"
    description = "Rejects breaking changes to the committed GraphQL schema"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ch.nokillswit.GraphqlCompatibilityCli")
    args(rootProject.layout.projectDirectory.asFile.absolutePath)
    providers.gradleProperty("graphqlBaselineRef").orNull?.let { args(it) }
    doNotTrackState("The result depends on the working tree and Git baseline history")
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
    dependsOn(checkGraphqlCompatibility)
}

// Static analysis (plain rule sets only — no type resolution). Rule tuning lives in
// config/detekt/detekt.yml; the task rides `check`, so `build` gates on it.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
dependencies {
    implementation(libs.graphql.java)
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
    implementation(libs.mcp.kotlin.sdk.server)
    // Pinned explicitly: the MCP SDK requests an older ktor-server-sse transitively (3.5.1 as of
    // SDK 0.15.0) — this keeps every Ktor artifact on the repo's 3.6.0 catalog line.
    implementation(ktorLibs.server.sse)
    implementation(libs.okhttp)
    implementation(libs.opentelemetry.logbackAppender)
    implementation(libs.postgresql)
    implementation(libs.r2dbc.pool)
    implementation(libs.r2dbc.postgresql)
    // Reactor alignment - see the `reactor-bom` comment in gradle/libs.versions.toml: the BOM pins
    // reactor-core/-pool/-netty to one release train (they carry different version numbers), so
    // these two declarations carry no version of their own.
    implementation(platform(libs.reactor.bom))
    implementation(libs.reactor.pool)
    // Netty alignment - see the `netty` comment in gradle/libs.versions.toml: the BOM floors every
    // io.netty module at one version, and reactor-netty (versioned by the Reactor BOM above) wins
    // over the driver's older transitive request (Gradle picks the highest). Guarded by
    // checkDependencyAlignment below.
    implementation(platform(libs.netty.bom))
    implementation(libs.reactor.netty.core)
    // Advisory floors - see the `jackson-bom`/`scram` comment in gradle/libs.versions.toml. Both
    // families arrive transitively only; the platform/constraints raise them to the fixed versions.
    implementation(platform(libs.jackson.bom))
    constraints {
        implementation(libs.scram.client) { because("CVE-2026-53712 (r2dbc-postgresql pulls 3.2)") }
        implementation(libs.scram.common) { because("CVE-2026-53712 (r2dbc-postgresql pulls 3.2)") }
    }

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

// Fails the build when a dependency FAMILY that must move as one resolves to several versions on
// the server runtime classpath. `platform()` only FLOORS a version, so a Ktor bump that needs Netty
// 4.2.19 would leave netty-resolver-dns & co. at the BOM's 4.2.17 - the mixed Netty set this guards
// against (see the `netty`/`reactor-bom`/`jackson-bom` notes in gradle/libs.versions.toml).
// Docker-free, rides `check` like detekt. Ported from Lettuce.
val checkDependencyAlignment by tasks.registering {
    group = "verification"
    description = "Asserts one version per aligned dependency family (Reactor: the BOM's versions) on the server runtime classpath."
    val runtimeClasspath = configurations.runtimeClasspath
    doLast {
        val ids = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
        // The Reactor modules are the exception to one-version-per-family (reactor-core 3.8.7 next
        // to reactor-pool 1.2.7 and reactor-netty 1.3.7 is ONE release train), so they are checked
        // against the Reactor BOM's own constraints instead - upstream's pairing table. Read off the
        // platform node of the resolution graph, so no extra artifact is fetched.
        val bomCoordinates = "io.projectreactor:reactor-bom"
        val bomNode = runtimeClasspath.get().incoming.resolutionResult.allComponents.singleOrNull {
            (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                ?.let { id -> "${id.group}:${id.module}" == bomCoordinates } == true
        } ?: error("$bomCoordinates is not on the runtime classpath - is the Reactor check still needed?")
        val managed = bomNode.dependencies
            .filter { it.isConstraint }
            .mapNotNull { it.requested as? org.gradle.api.artifacts.component.ModuleComponentSelector }
            .associate { "${it.group}:${it.module}" to it.version }
        val reactorIds = ids.filter { it.group.startsWith("io.projectreactor") }
        check(reactorIds.isNotEmpty()) { "No io.projectreactor module on the runtime classpath - is the Reactor check still needed?" }
        val reactorDrift = reactorIds.mapNotNull { id ->
            when (val declared = managed["${id.group}:${id.name}"]) {
                null -> "${id.group}:${id.name}:${id.version} is not in reactor-bom"
                id.version -> null
                else -> "${id.group}:${id.name} resolved ${id.version}, reactor-bom declares $declared"
            }
        }
        if (reactorDrift.isEmpty()) {
            logger.lifecycle(
                "io.projectreactor aligned with reactor-bom ${libs.versions.reactor.bom.get()} (" +
                    reactorIds.joinToString { "${it.name} ${it.version}" } + ")",
            )
        }
        // family label -> (member predicate, version normalizer)
        val families = mapOf(
            "io.netty" to Pair({ g: String, _: String -> g == "io.netty" }, { v: String -> v }),
            // Jackson: jackson-annotations is released per MINOR only since 2.20 ("2.22", no patch
            // level) while core/databind/datatype carry patches ("2.22.3"), so one exact-version
            // family would always trip. Two checks instead: every module on the SAME major.minor
            // line (what breaks at runtime - a databind 2.21 over a core 2.22), and every module
            // except annotations on the SAME major.minor.patch (what the jackson-bom floors).
            "jackson (major.minor line)" to Pair(
                { g: String, _: String -> g.startsWith("com.fasterxml.jackson") },
                { v: String -> v.split('.').take(2).joinToString(".") },
            ),
            "jackson (patch, annotations excluded)" to Pair(
                { g: String, n: String -> g.startsWith("com.fasterxml.jackson") && n != "jackson-annotations" },
                { v: String -> v.split('.').take(3).joinToString(".") },
            ),
        )
        val drift = families.mapNotNull { (label, spec) ->
            val (member, normalize) = spec
            val versions = ids.filter { member(it.group, it.name) }.groupBy({ normalize(it.version) }, { it.name })
            if (versions.size == 1) {
                logger.lifecycle("$label aligned at ${versions.keys.single()} (${versions.values.single().size} modules)")
                null
            } else {
                "$label: " + versions.entries.joinToString("; ") { (v, names) -> "$v -> ${names.sorted()}" }
            }
        }
        check(drift.isEmpty() && reactorDrift.isEmpty()) {
            "Dependency families must resolve to ONE version each on the runtime classpath, and Reactor modules " +
                "to the reactor-bom's versions - " + (drift + reactorDrift).joinToString(" | ")
        }
    }
}
tasks.named("check") { dependsOn(checkDependencyAlignment) }
