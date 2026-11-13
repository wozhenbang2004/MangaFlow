import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna:5.19.1")
}

val mangaVersion = providers.gradleProperty("mangaVersionName").orElse("0.1.0")

compose.desktop {
    application {
        mainClass = "dev.mangaflow.desktop.MainKt"
        jvmArgs += listOf("-Dmangaflow.version=${mangaVersion.get()}", "-Dcompose.interop.blending=true", "-Djava.net.useSystemProxies=true")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "MangaFlow"
            packageVersion = mangaVersion.get().substringBefore('-')
            description = "MangaFlow manga reader"
            vendor = "MangaFlow"
            modules("java.desktop", "java.net.http", "java.naming", "java.management", "java.scripting", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility", "jdk.jsobject", "jdk.xml.dom", "java.xml", "java.logging", "java.datatransfer", "jdk.unsupported.desktop", "java.prefs", "java.sql", "jdk.zipfs")
            windows {
                menuGroup = "MangaFlow"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6e16a804-1c0c-4a84-a348-631288194ff9"
                iconFile.set(project.file("icons/mangaflow.ico"))
            }
        }
    }
}

tasks.withType<Jar>().configureEach { manifest.attributes["Implementation-Version"] = mangaVersion.get() }
tasks.withType<Test>().configureEach { enabled = false }
