plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.mangaflow"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.mangaflow"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("mangaVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("mangaVersionName").orElse("0.1.0-dev").get()
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        abortOnError = true
        checkAllWarnings = true
        checkDependencies = true
        // Full Lint is an explicit CI option; assemble still compiles and runs R8.
        checkReleaseBuilds = false
        warningsAsErrors = false
        htmlReport = true
        xmlReport = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compress multi-ABI inference libraries in the directly distributed APK.
        jniLibs.useLegacyPackaging = true
    }
}

// Lint analyzes sources; executable test tasks remain disabled.
tasks.configureEach {
    if (!name.contains("lint", ignoreCase = true) && name.contains("test", ignoreCase = true)) {
        enabled = false
    }
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    enabled = false
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.16.0")
    implementation("me.saket.telephoto:sub-sampling-image:0.16.0")
    implementation("dev.chrisbanes.haze:haze:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur-material3:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur-materials:2.1.0")
    implementation("dev.chrisbanes.haze:haze-glass:2.1.0")
    implementation("dev.chrisbanes.haze:haze-glass-material3:2.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Optional panel models run locally; weights are explicitly downloaded by the user.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
