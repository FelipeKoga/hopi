import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

description = "Hopi.start { }, the iOS entry a Debug framework exports and the JVM entry: it starts the network and perf tools."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

// The iOS and JVM entry (docs/ARCHITECTURE.md, "Consumer API"): `Hopi.shared.start { … }` from
// Swift, or `Hopi.start { … }` first in a JVM app's `main`, runs core's start sequence with the
// network tool and perf's. Only a Debug framework links and exports it; its public API names no
// core or runtime type, so it adds `Hopi` and `HopiStartConfig` to Swift's header and nothing else.
// A JVM app names it only where it records (there is no JVM release guard).
kotlin {
    explicitApi()

    // The consumer API dump: Hopi and HopiStartConfig; everything else here is internal.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
    }

    jvm()
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
        jvmTest.dependencies {
            // A Ktor client built after the JVM start, recorded through the plugin (hopi-network
            // takes hopi-ktor compileOnly, so it does not come with the runtime).
            implementation(project(":hopi-ktor"))
            implementation(libs.ktor.client.mock)
        }
    }
}

// KGP 2.2's ABI check (the dumps in api/, `./gradlew :hopi-start:updateLegacyAbi`) is not part of `check` by itself.
tasks.named("check") { dependsOn("checkLegacyAbi") }

// `-Phopi.simulator=<udid>` runs the iOS tests on that already booted simulator (see hopi-core).
providers.gradleProperty("hopi.simulator").orNull?.let { udid ->
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        device.set(udid)
        standalone.set(false)
    }
}
