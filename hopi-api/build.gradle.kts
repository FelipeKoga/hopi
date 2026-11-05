import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "The API an app calls; it does nothing until a debug build's runtime registers."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    // The consumer API dump (HopiRules and its builders, the same as hopi-rules' before the
    // runtime-split plan's stage 7; HopiPerf, HopiMonitor and HopiPerfConfig, hopi-perf's before its
    // stage 8 less install and the start configuration's three settings); Hopi's own declarations
    // stay out of it: the registry, the rules and perf runtimes' interfaces and the builders'
    // members that show the runtime what was set.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        filters {
            excluded {
                annotatedWith.add("dev.koga.hopi.api.HopiInternalApi")
            }
        }
    }

    android {
        namespace = "dev.koga.hopi.api"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // An app needs compileSdk 34, not this module's: AGP would otherwise write compileSdk into
        // the AAR's minCompileSdk, and an older AGP refuses an AAR that asks for more than it supports.
        aarMetadata { minCompileSdk = libs.versions.android.minCompileSdk.get().toInt() }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // No dependency at all: release builds link this module.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// KGP 2.2's ABI check (the dumps in api/, `./gradlew :hopi-api:updateLegacyAbi`) is not part of `check` by itself.
tasks.named("check") { dependsOn("checkLegacyAbi") }
