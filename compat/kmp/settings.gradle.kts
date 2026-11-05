// A Kotlin Multiplatform consumer of Hopi as an app gets it: the io.github.felipekoga.hopi plugin and hopi-ktor,
// resolved from the repositories :checkPublication fills (run it first), never Maven Local. The
// Kotlin Gradle plugin is -Pkgp's (default 2.4.20, the pilot app's), Ktor -Pktor's (default 3.5.2).
// It builds with the repository's wrapper or the floor's Gradle 8.0.2 (compat/floor-gradle):
//
//   ./gradlew -p compat/kmp -Pkgp=2.4.20 :shared:linkDebugFrameworkIosSimulatorArm64 -Phopi.enabled=true
//   ./gradlew -p compat/kmp -Pkgp=2.4.20 :shared:linkReleaseFrameworkIosSimulatorArm64
//   GRADLE_USER_HOME=build/compat-home compat/floor-gradle/gradlew --no-daemon -p compat/kmp -Pkgp=2.2.21 -Pktor=3.3.3 :shared:linkReleaseFrameworkIosSimulatorArm64
//
// compat/run.sh runs them (see compat/README.md).
rootProject.name = "compat-kmp"

pluginManagement {
    // compat/kmp is two levels below the repository.
    val repository = settingsDir.resolve("../..").canonicalFile
    val kgp = providers.gradleProperty("kgp").getOrElse("2.4.20")
    repositories {
        // The io.github.felipekoga.hopi plugin, its marker and hopi-instrument (the plugin's functional-test
        // repository, which :checkPublication publishes them into).
        maven(repository.resolve("gradle-plugin/build/functional-repo"))
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kgp
        id("io.github.felipekoga.hopi") version "0.1.0"
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

include(":shared")
