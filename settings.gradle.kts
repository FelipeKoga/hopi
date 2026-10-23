rootProject.name = "hopi"

pluginManagement {
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

include(":hopi-core")
include(":hopi-ktor")
include(":hopi-ktor-noop")
include(":hopi-ui")
include(":hopi-ui-noop")
include(":hopi-cli")
include(":sample:shared")
include(":sample:androidApp")
