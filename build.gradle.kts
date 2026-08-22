
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

subprojects {
    group = "ch.nokillswit"
    version = "1.0.0-SNAPSHOT"
}

dependencies {
    kover(project(":server"))
}
