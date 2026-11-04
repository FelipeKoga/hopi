// An Android app as an app gets Hopi: the dev.koga.hopi plugin, hopi-api and hopi-ktor, resolved
// from the repositories :checkPublication fills (run it first), never Maven Local. AGP is -Pagp's
// (default 8.0.2, Hopi's floor), the Kotlin Gradle plugin -Pkgp's (default 2.2.0, the floor). It
// builds with the floor's Gradle 8.0.2 (compat/floor-gradle) or the repository's wrapper, with
// ANDROID_HOME set or a local.properties here naming the SDK:
//
//   GRADLE_USER_HOME=build/compat-home compat/floor-gradle/gradlew --no-daemon -p compat/android :app:assembleDebug :app:assembleRelease
//   ./gradlew -p compat/android -Pagp=9.4.0 -Pkgp=2.4.20 :app:assembleDebug :app:assembleRelease
//
// compat/run.sh runs both (see compat/README.md).
rootProject.name = "compat-android"

pluginManagement {
    // compat/android is two levels below the repository.
    val repository = settingsDir.resolve("../..").canonicalFile
    val agp = providers.gradleProperty("agp").getOrElse("8.0.2")
    val kgp = providers.gradleProperty("kgp").getOrElse("2.2.0")
    repositories {
        // The dev.koga.hopi plugin, its marker and hopi-instrument (the plugin's functional-test
        // repository, which :checkPublication publishes them into).
        maven(repository.resolve("gradle-plugin/build/functional-repo"))
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version agp
        id("org.jetbrains.kotlin.android") version kgp
        id("dev.koga.hopi") version "0.1.0"
    }
}

dependencyResolutionManagement {
    repositories {
        // Every Hopi library, as :checkPublication publishes it.
        maven(settingsDir.resolve("../../build/publication-check").canonicalFile)
        google()
        mavenCentral()
    }
}

include(":app")
