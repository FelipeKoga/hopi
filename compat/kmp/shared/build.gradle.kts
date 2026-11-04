// An app's shared module: a JVM target and an iOS simulator framework, Hopi's Gradle plugin (hopi-api
// in every build, hopi-start and the runtimes in a Debug one, the iOS release guard on every Release
// link) and the thin Ktor plugin, hopi-ktor, next to the app's own Ktor.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("dev.koga.hopi")
}

val ktor = providers.gradleProperty("ktor").getOrElse("3.5.2")

kotlin {
    jvm()
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "Shared"
            // A symbol in Hopi's klibs that this app's Ktor or stdlib cannot supply fails this link.
            // Without the flag Kotlin/Native links in a stub that throws IrLinkageError when it is
            // called, and reports the stub at a level Gradle does not print (2.4: SILENT, 2.2: INFO).
            freeCompilerArgs += "-Xpartial-linkage-loglevel=ERROR"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("dev.koga.hopi:hopi-ktor:0.1.0")
            implementation("io.ktor:ktor-client-core:$ktor")
        }
    }
}
