// Standalone build: the pure-Kotlin game core compiles and tests without the Android SDK.
// The Android app consumes it as an included build (see ../settings.gradle.kts).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "core"
