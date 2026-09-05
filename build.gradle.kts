
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

// Dependency locking (the deployment-readiness audit, v1.22.0): every resolvable configuration in
// every project — compile/runtime/test classpaths, the Kotlin compiler and detekt classpaths, and
// the buildscript (plugin) classpath — is pinned to the committed `gradle.lockfile`s. The version
// catalog pins DIRECT versions; the lockfiles pin the TRANSITIVE graph, so two checkouts resolve
// byte-identical classpaths. DEFAULT lock mode: a resolved version outside the lock state fails
// the build. After ANY dependency change run `./gradlew build --write-locks` and commit the
// lockfiles — never delete a lockfile to get past a "lock state" error. Verification metadata
// (checksums per artifact) is deliberately NOT adopted — a maintenance sink with no bump bot.
buildscript {
    dependencyLocking {
        lockAllConfigurations()
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
