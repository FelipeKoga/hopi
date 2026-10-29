import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

description = "Hopi.start { }, the iOS entry a Debug framework exports: it starts the network and perf tools."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

// The iOS entry (docs/ARCHITECTURE.md, "Consumer API"): `Hopi.shared.start { … }` from Swift runs
// core's start sequence with the network tool and perf's. Only a Debug framework links and exports
// it; its public API names no core or runtime type, so it adds `Hopi` and `HopiStartConfig` to
// Swift's header and nothing else.
kotlin {
    explicitApi()

    // The consumer API dump: Hopi and HopiStartConfig; everything else here is internal.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
    }

    // The runtime-split plan's stage 10 adds the JVM entry.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // implementation, not api: the framework exports this module alone, so none of their
            // types reach Swift. hopi-api comes through core; the network tool is the network
            // runtime's (hopi-network), the perf tool hopi-perf's.
            implementation(project(":hopi-core"))
            implementation(project(":hopi-network"))
            implementation(project(":hopi-perf"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// `-Phopi.simulator=<udid>` runs the iOS tests on that already booted simulator (see hopi-core).
providers.gradleProperty("hopi.simulator").orNull?.let { udid ->
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        device.set(udid)
        standalone.set(false)
    }
}
