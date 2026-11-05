// The Gradle plugin `io.github.felipekoga.hopi` and the bytecode rewriter it shares with hopi-network's tests
// (docs/design/android-capture.md). An included build of the root one, never a runtime dependency
// of an app: it is both a plugin build (`pluginManagement { includeBuild }`) and a regular included
// build, so hopi-network's host tests get `io.github.felipekoga.hopi:hopi-instrument` substituted from here.
rootProject.name = "gradle-plugin"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

include(":hopi-instrument")
include(":hopi-gradle-plugin")
