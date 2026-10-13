
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

// Dependency locking (the deployment-readiness audit, v1.22.0): every resolvable configuration in
// every project — compile/runtime/test classpaths, the Kotlin compiler and detekt classpaths — is
// pinned to the committed `gradle.lockfile`s, and each project's buildscript (plugin) classpath to
// its `buildscript-gradle.lockfile` (this block locks the root's; `core/` and `server/` activate
// locking for their own `classpath` in their buildscript blocks). The version catalog pins DIRECT
// versions; the lockfiles pin the TRANSITIVE graph, so two checkouts resolve byte-identical
// classpaths. DEFAULT lock mode: a resolved version outside the lock state fails the build.
// After ANY dependency change regenerate the lockfiles AND the verification metadata TOGETHER with
// the command in `.claude/docs/dependency-reproducibility.md` (`./gradlew build --write-locks`
// alone is NOT enough) — never delete a lockfile to get past a "lock state" error.
buildscript {
    dependencyLocking {
        lockAllConfigurations()
    }
    dependencies {
        constraints {
            // Keep the root plugin classpath (Kover's reporter) above the FreeMarker path-traversal
            // advisory floor.
            classpath("org.freemarker:freemarker:2.3.35")
        }
    }
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

subprojects {
    group = "ch.nokillswit"
    version = "1.0.0-SNAPSHOT"
}

dependencies {
    kover(project(":server"))
}
