import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.compose") version "1.9.0"
}

group = "dev.lelonio.square"
version = "1.0.0"

val ktor = "3.5.2"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
    // The InnerTube client is shared with the Android app as source: it is plain
    // Kotlin, and an Android library module cannot be depended on from here.
    sourceSets.main {
        kotlin.srcDir("../innertube/src/main/kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.json:json:20250517")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Images: Coil 3 runs on the desktop JVM and fetches over Ktor.
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")

    // What the vendored InnerTube client needs; see innertube/build.gradle.kts.
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-encoding:$ktor")
    implementation("org.brotli:dec:0.1.2")
    // Anonymous YouTube Music search and stream URLs; pure JVM.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")
}

compose.desktop {
    application {
        mainClass = "dev.lelonio.square.desktop.MainKt"
        jvmArgs += listOf("-Xmx768m", "-Dapple.awt.application.appearance=system")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Square"
            packageVersion = "1.0.0"
            description = "Spotify and YouTube Music, on the Mac"
            copyright = "GPL-3.0"
            // The Rust engine (libsquarecore.dylib) is copied into
            // resources/macos-arm64 by CI or by scripts/build-native-mac.sh.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules(
                "java.net.http",
                "java.sql",
                "java.naming",
                "java.management",
                "java.prefs",
                "jdk.crypto.ec",
                "jdk.unsupported",
                "jdk.httpserver",
                "java.xml",
                "java.scripting",
                "jdk.zipfs",
                "jdk.charsets",
                "jdk.localedata",
                "jdk.accessibility",
            )
            macOS {
                bundleID = "dev.lelonio.square.desktop"
                appCategory = "public.app-category.music"
                dockName = "Square"
                val icon = project.file("icons/Square.icns")
                if (icon.exists()) iconFile.set(icon)
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSAppTransportSecurity</key>
                        <dict><key>NSAllowsLocalNetworking</key><true/></dict>
                    """.trimIndent()
                }
            }
        }
    }
}
