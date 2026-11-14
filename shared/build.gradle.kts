import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep Java/Kotlin APIs in the base APK; native engines are signed optional assets.
val inferenceRuntimeAars = configurations.create("inferenceRuntimeAars") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val optionalOnnxApi = tasks.register<org.gradle.api.tasks.bundling.Jar>("prepareOptionalOnnxApi") {
    archiveFileName.set("onnxruntime-android-1.31.1-api.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/optional-inference-api"))
    inputs.files(inferenceRuntimeAars)
    from({
        val aar = inferenceRuntimeAars.resolvedConfiguration.resolvedArtifacts.single {
            it.moduleVersion.id.group == "com.microsoft.onnxruntime" && it.name == "onnxruntime-android"
        }.file
        val classes = zipTree(aar).matching { include("classes.jar") }.singleFile
        zipTree(classes)
    }) {
        // The compiled-in source adaptation supports verified absolute native paths on Android.
        exclude("ai/onnxruntime/OnnxRuntime.class", "ai/onnxruntime/OnnxRuntime\$*.class")
    }
}

// Android retains the pinned JM client while binding its image tool to Bitmap/Canvas.
// The upstream core jar also contains a desktop AWT implementation and reflective fallback.
val androidJmCoreJar = configurations.create("androidJmCoreJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val prepareAndroidJmCore = tasks.register<org.gradle.api.tasks.bundling.Jar>("prepareAndroidJmCore") {
    archiveFileName.set("jmcomic-core-v1.1.12-android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/jm-android-core"))
    inputs.files(androidJmCoreJar)
    val replacedClasses = setOf(
        "io/github/jukomu/jmcomic/core/image/AwtImageProcessor.class",
        "io/github/jukomu/jmcomic/core/crypto/JmImageTool.class",
    )
    doFirst {
        ZipFile(androidJmCoreJar.singleFile).use { source ->
            replacedClasses.forEach { path ->
                check(source.getEntry(path) != null) { "Pinned JM core is missing expected class: $path" }
            }
        }
    }
    from({ zipTree(androidJmCoreJar.singleFile) }) {
        exclude("io/github/jukomu/jmcomic/core/image/AwtImageProcessor.class")
        exclude("io/github/jukomu/jmcomic/core/image/AwtImageProcessor\$*.class")
        exclude("io/github/jukomu/jmcomic/core/crypto/JmImageTool.class")
        exclude("io/github/jukomu/jmcomic/core/crypto/JmImageTool\$*.class")
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { output ->
            replacedClasses.forEach { path ->
                check(output.getEntry(path) == null) { "Desktop JM class remained in Android core: $path" }
            }
            check(output.getEntry("io/github/jukomu/jmcomic/core/image/spi/ImageProcessor.class") != null)
            check(output.getEntry("io/github/jukomu/jmcomic/core/client/impl/JmApiClient.class") != null)
        }
    }
}

tasks.register("stageInferenceAars") {
    val output = layout.buildDirectory.dir("outputs/inference-aars")
    inputs.files(inferenceRuntimeAars)
    outputs.dir(output)
    doLast {
        val expected = mapOf("litert" to "litert-1.4.2.aar",
            "onnxruntime-android" to "onnxruntime-android-1.31.1.aar",
            "quickjs-kt-android" to "quickjs-kt-android-1.0.18.aar")
        val artifacts = inferenceRuntimeAars.resolvedConfiguration.resolvedArtifacts
        for ((name, fileName) in expected) {
            val source = artifacts.single { it.name == name }.file
            val destination = output.get().file(fileName).asFile
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }
}

kotlin {
    android {
        namespace = "dev.mangaflow.shared"
        compileSdk {
            version = release(37) { minorApiLevel = 0 }
        }
        buildToolsVersion = "36.0.0"
        minSdk = 26
        withJava()
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

    applyDefaultHierarchyTemplate()
    sourceSets {
        val nonAndroidMain = create("nonAndroidMain") { dependsOn(commonMain.get()) }
        val desktopMain = getByName("desktopMain")
        // Compile the small Java socket/crypto receiver for each JVM platform.
        getByName("androidMain").kotlin.srcDir("src/jvmMain/kotlin")
        desktopMain.kotlin.srcDir("src/jvmMain/kotlin")
        desktopMain.dependsOn(nonAndroidMain)
        iosMain.get().dependsOn(nonAndroidMain)
        commonMain.dependencies {
            implementation("io.github.alexzhirkevich:qrose:1.3.1")
            api(compose.runtime)
            api(compose.ui)
            api(compose.foundation)
            api(compose.animation)
            api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            api("org.jetbrains.compose.ui:ui-backhandler:1.12.2")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:atomicfu:0.29.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            api("com.squareup.okio:okio:3.16.2")
            api("io.ktor:ktor-client-core:3.6.1")
            api("io.ktor:ktor-client-encoding:3.6.1")
            api("com.fleeksoft.ksoup:ksoup:0.2.6")
            api("io.coil-kt.coil3:coil-compose:3.2.0")
            api("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
            api("me.saket.telephoto:zoomable:0.16.0")
            api("dev.chrisbanes.haze:haze:2.1.0")
            api("dev.chrisbanes.haze:haze-blur:2.1.0")
            api("dev.chrisbanes.haze:haze-blur-material3:2.1.0")
            api("dev.chrisbanes.haze:haze-blur-materials:2.1.0")
            api("dev.chrisbanes.haze:haze-glass:2.1.0")
            api("dev.chrisbanes.haze:haze-glass-material3:2.1.0")
            // Matches the stable Material 3 line used by the existing Android BOM.
            api("org.jetbrains.compose.material3:material3:1.9.0")
        }
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.16.0")
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.webkit:webkit:1.14.0")
            implementation("androidx.exifinterface:exifinterface:1.4.1")
            implementation("androidx.work:work-runtime-ktx:2.10.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
            implementation("io.ktor:ktor-client-okhttp:3.6.1")
            implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("org.jsoup:jsoup:1.18.3")
            // Native adult source adapters (MIT). Java API code is shared with Windows.
            implementation(files(prepareAndroidJmCore.flatMap { it.archiveFile }))
            implementation("io.github.jukomu:jmcomic-api:v1.1.12")
            implementation("io.github.jukomu:jmcomic-android-support:v1.1.12") {
                exclude(group = "io.github.jukomu", module = "jmcomic-core")
            }
            // Non-desktop dependencies from core v1.1.12/5a7a4bb's POM.
            // OkHttp and Jsoup already use the app's compatible Android versions above.
            implementation("org.apache.commons:commons-lang3:3.18.0")
            implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
            implementation("com.google.code.gson:gson:2.13.2")
            implementation("org.slf4j:slf4j-api:2.0.12")
            implementation("me.saket.telephoto:zoomable-image-coil3:0.16.0")
            implementation("me.saket.telephoto:sub-sampling-image:0.16.0")
            implementation("com.google.ai.edge.litert:litert:1.4.2")
            implementation(files(optionalOnnxApi.flatMap { it.archiveFile }))
            implementation("io.github.dokar3:quickjs-kt:1.0.18")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.update4j:update4j:1.5.9")
            implementation("io.ktor:ktor-client-okhttp:3.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            implementation("com.ibm.icu:icu4j:77.1")
            implementation("io.github.darkxanter:webp-imageio:0.3.3")
            implementation("io.github.jukomu:jmcomic-core:v1.1.12")
            // JavaFX's native classifiers follow the host used by jpackage.
            val osName = System.getProperty("os.name").lowercase()
            val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
            val javafxPlatform = when {
                osName.contains("win") -> if (arm) "win-aarch64" else "win"
                osName.contains("mac") -> if (arm) "mac-aarch64" else "mac"
                else -> if (arm) "linux-aarch64" else "linux"
            }
            listOf("base", "graphics", "controls", "media", "web", "swing").forEach { module ->
                implementation("org.openjfx:javafx-$module:21.0.9:$javafxPlatform")
            }
        }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.6.1") }
    }
}

androidJmCoreJar.dependencies.add(dependencies.create("io.github.jukomu:jmcomic-core:v1.1.12"))

inferenceRuntimeAars.dependencies.add(dependencies.create("com.google.ai.edge.litert:litert:1.4.2@aar"))
inferenceRuntimeAars.dependencies.add(dependencies.create("com.microsoft.onnxruntime:onnxruntime-android:1.31.1@aar"))
inferenceRuntimeAars.dependencies.add(dependencies.create("io.github.dokar3:quickjs-kt-android:1.0.18@aar"))

// This repository's delivery policy permits compilation/packaging, not executable tests.
tasks.configureEach {
    if (name.contains("test", ignoreCase = true) && !name.contains("lint", ignoreCase = true)) {
        enabled = false
    }
}
