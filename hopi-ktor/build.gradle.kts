import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "The Hopi Ktor client plugin, in every build: it installs the recorder a debug build's network runtime registered, and nothing without one."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

kotlin {
    explicitApi()

    // The consumer API dump: Hopi, HopiConfig and HopiLogSink, unchanged by the runtime-split
    // plan's stage 10. Hopi's own declarations stay out of it: the hooks interface the network
    // runtime implements and the configuration's members that show the recorder what was set.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        filters {
            excluded {
                annotatedWith.add("dev.koga.hopi.api.HopiInternalApi")
            }
        }
    }

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
    }

    android {
        namespace = "dev.koga.hopi.ktor"
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
        commonMain.dependencies {
            // The consumer API names ClientPlugin; no core type appears in it, and nothing of core
            // is here: the recorder is the network runtime's (hopi-network).
            api(libs.ktor.client.core)
            // The registry's Ktor slot and the @HopiInternalApi marker. api, not implementation: a
            // consumer that calls a marked member (HopiConfig's three, HopiKtorHooks) needs the
            // marker on its classpath for the opt-in to be enforced, and every consumer gets
            // HopiRules and HopiPerf with it. hopi-api has no dependency and is release-allowed.
            api(project(":hopi-api"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// KGP 2.2's ABI check (the dumps in api/, `./gradlew :hopi-ktor:updateLegacyAbi`) is not part of `check` by itself.
tasks.named("check") { dependsOn("checkLegacyAbi") }

// `-Phopi.ktorForward=true` runs the tests on the Ktor the pilot app runs (the catalog's ktor-forward):
// io.ktor moves on the JVM tests' runtime classpath only, while everything still compiles against
// `ktor`, the Ktor consumers get from Hopi (see hopi-network, whose recorder uses Ktor internals).
if (providers.gradleProperty("hopi.ktorForward").map(String::toBoolean).getOrElse(false)) {
    val ktorForward = libs.versions.ktor.forward.get()
    configurations.matching { it.name.endsWith("TestRuntimeClasspath") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.ktor") {
                useVersion(ktorForward)
                because("-Phopi.ktorForward=true")
            }
        }
    }
}
