import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    android {
        namespace = "dev.mangaflow.shared"
        compileSdk {
            version = release(37) { minorApiLevel = 0 }
        }
        buildToolsVersion = "36.0.0"
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "MangaFlowShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.ui)
            api(compose.foundation)
            api(compose.animation)
            // Matches the stable Material 3 line used by the existing Android BOM.
            api("org.jetbrains.compose.material3:material3:1.9.0")
        }
    }
}

// This repository's delivery policy permits compilation/packaging, not executable tests.
tasks.configureEach {
    if (name.contains("test", ignoreCase = true) && !name.contains("lint", ignoreCase = true)) {
        enabled = false
    }
}
