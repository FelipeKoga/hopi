rootProject.name = "hopi"

pluginManagement {
    // The `dev.koga.hopi` Gradle plugin (docs/design/android-capture.md).
    includeBuild("gradle-plugin")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
}

// Also a regular included build: hopi-android's host tests use its rewriter (hopi-instrument).
includeBuild("gradle-plugin")

include(":hopi-api")
include(":hopi-core")
include(":hopi-ktor")
include(":hopi-ktor-noop")
include(":hopi-ui")
include(":hopi-ui-noop")
include(":hopi-urlsession")
include(":hopi-urlsession-noop")
include(":hopi-android")
include(":hopi-android-noop")
include(":hopi-rules")
include(":hopi-rules-noop")
include(":hopi-perf")
include(":hopi-perf-noop")
include(":hopi-connect")
include(":hopi-cli")
include(":hopi-desktop")
include(":sample:shared")
include(":sample:fakesdk")
include(":sample:androidApp")
