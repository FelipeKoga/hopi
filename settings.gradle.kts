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

// Also a regular included build: hopi-network's host tests use its rewriter (hopi-instrument).
includeBuild("gradle-plugin")

include(":hopi-api")
include(":hopi-core")
include(":hopi-ktor")
include(":hopi-ktor-noop")
include(":hopi-ui")
include(":hopi-network")
include(":hopi-perf")
include(":hopi-start")
include(":hopi-connect")
include(":hopi-cli")
include(":hopi-desktop")
include(":sample:shared")
include(":sample:fakesdk")
include(":sample:androidApp")
