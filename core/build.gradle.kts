
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
}

// Static analysis, same config as :server. Detekt's default source roots are the plain-JVM
// src/main|test/kotlin, so the KMP source set is listed explicitly.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/commonMain/kotlin")
}


kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.opentelemetry.exporterLogging)
            api(libs.opentelemetry.exporterOtlp)
            api(libs.opentelemetry.ktorInstrumentation)
            api(libs.opentelemetry.sdkAutoconfigure)
            api(libs.opentelemetry.semconv)
        }

        commonTest.dependencies {
            kotlin("test")
        }
    }
}
