// The Mac app is its own Gradle build rather than a module of the Android one:
// configuring the root project needs the Android SDK, which a Mac building a
// desktop app has no reason to have. Run it with the root wrapper:
//
//     ./gradlew -p desktop run
//     ./gradlew -p desktop packageDmg

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // NewPipeExtractor publishes nowhere else; narrowed to its own group.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
    }
}

rootProject.name = "square-desktop"
