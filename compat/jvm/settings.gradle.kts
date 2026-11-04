// A JVM app as an app gets Hopi: hopi-start and hopi-ktor, resolved from the repository
// :checkPublication fills (run it first), never Maven Local. The Kotlin Gradle plugin is -Pkgp's
// (default 2.2.0, Hopi's floor). It builds with the floor's Gradle 8.0.2 (compat/floor-gradle) or
// the repository's wrapper:
//
//   GRADLE_USER_HOME=build/compat-home compat/floor-gradle/gradlew --no-daemon -p compat/jvm run
//   ./gradlew -p compat/jvm -Pkgp=2.4.20 run
//
// compat/run.sh runs both (see compat/README.md).
rootProject.name = "compat-jvm"

pluginManagement {
    val kgp = providers.gradleProperty("kgp").getOrElse("2.2.0")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version kgp
    }
}

dependencyResolutionManagement {
    repositories {
        // Every Hopi library, as :checkPublication publishes it; compat/jvm is two levels below the
        // repository.
        maven(settingsDir.resolve("../../build/publication-check").canonicalFile)
        mavenCentral()
    }
}
