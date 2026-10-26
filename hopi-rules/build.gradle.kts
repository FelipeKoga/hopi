import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "HopiRules: mock, fail, delay and edit rules from Kotlin or Swift code."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

// HopiRules: the in-process API over core's rule engine (docs/design/rules.md, "In-process API"),
// for tests and debug menus. Its noop twin, hopi-rules-noop, has the same API and does nothing.
kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with hopi-rules-noop's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        optIn.add("dev.koga.hopi.core.HopiInternalApi")
    }

    android {
        namespace = "dev.koga.hopi.rules"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The engine; none of its types are in this module's API.
            implementation(project(":hopi-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
