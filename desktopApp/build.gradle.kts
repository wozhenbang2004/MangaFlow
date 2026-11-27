import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import java.io.RandomAccessFile

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
    implementation("net.java.dev.jna:jna-platform:5.18.1")
}

val mangaVersion = providers.gradleProperty("mangaVersionName").orElse("0.1.0")

compose.desktop {
    application {
        mainClass = "dev.mangaflow.desktop.MainKt"
        jvmArgs += listOf("-Dmangaflow.version=${mangaVersion.get()}", "-Dcompose.interop.blending=true", "-Djava.net.useSystemProxies=true")
        buildTypes.release.proguard {
            optimize.set(false)
            obfuscate.set(false)
            joinOutputJars.set(false)
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            // Installed launcher and configuration names are also used by the updater and Relay.
            packageName = "MangaFlow"
            packageVersion = mangaVersion.get().substringBefore('-')
            description = "Kayo"
            vendor = "Kayo"
            modules("java.desktop", "java.net.http", "java.naming", "java.management", "jdk.management", "jdk.jfr", "jdk.management.jfr", "java.scripting", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility", "jdk.jsobject", "jdk.xml.dom", "java.xml", "java.logging", "java.datatransfer", "jdk.unsupported.desktop", "java.prefs", "java.sql", "jdk.zipfs")
            windows {
                menuGroup = "Kayo"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6e16a804-1c0c-4a84-a348-631288194ff9"
                iconFile.set(project.file("icons/mangaflow.ico"))
            }
        }
    }
}

// JDK 21 derives MSI product and shortcut captions from the stable launcher filename.
val windowsInstallerResources = layout.projectDirectory.dir("packaging/windows")
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Msi || targetFormat == TargetFormat.Exe) {
        inputs.dir(windowsInstallerResources)
        freeArgs.addAll("--resource-dir", "\"${windowsInstallerResources.asFile.invariantSeparatorsPath}\"")
    }
}

tasks.withType<Jar>().configureEach { manifest.attributes["Implementation-Version"] = mangaVersion.get() }
tasks.withType<Test>().configureEach { enabled = false }

tasks.withType<AbstractProguardTask>().configureEach {
    // ProGuard writes wall-clock DOS timestamps into every output JAR. Normalize
    // its output before both jpackage routes copy it into installers and app images.
    doLast {
        destinationDir.get().asFile.listFiles { file -> file.extension == "jar" }.orEmpty().forEach { jar ->
            RandomAccessFile(jar, "rw").use { zip ->
                var end = zip.length() - 22
                val first = (end - 65_535).coerceAtLeast(0)
                while (end >= first) {
                    zip.seek(end)
                    if (zip.readInt() == 0x504b0506) {
                        zip.seek(end + 20)
                        if (zip.readZipShort() == zip.length() - end - 22) break
                    }
                    end--
                }
                check(end >= first) { "Missing ZIP end record in ${jar.name}" }
                zip.seek(end + 8)
                val diskEntries = zip.readZipShort()
                val entries = zip.readZipShort()
                val directorySize = zip.readZipInt()
                var central = zip.readZipInt()
                check(entries == diskEntries && entries < 65_535 && central + directorySize == end) {
                    "Unsupported ProGuard ZIP layout in ${jar.name}"
                }
                repeat(entries.toInt()) {
                    zip.seek(central)
                    check(zip.readInt() == 0x504b0102) { "Invalid ZIP directory in ${jar.name}" }
                    zip.seek(central + 28)
                    val variableBytes = zip.readZipShort() + zip.readZipShort() + zip.readZipShort()
                    zip.seek(central + 42)
                    val local = zip.readZipInt()
                    check(local + 30 <= central) { "Invalid ZIP entry offset in ${jar.name}" }
                    zip.seek(local)
                    check(zip.readInt() == 0x504b0304) { "Invalid ZIP entry in ${jar.name}" }
                    // 1980-01-01 00:00 in little endian. Leave compressed bytes and alignment intact.
                    zip.seek(local + 10)
                    zip.writeInt(0x00002100)
                    zip.seek(central + 12)
                    zip.writeInt(0x00002100)
                    central += 46 + variableBytes
                }
                check(central == end) { "Unexpected ZIP directory data in ${jar.name}" }
            }
        }
    }
}

private fun RandomAccessFile.readZipShort(): Long {
    val low = readUnsignedByte()
    return (low or (readUnsignedByte() shl 8)).toLong()
}

private fun RandomAccessFile.readZipInt(): Long = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
