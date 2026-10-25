import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// What the `dev.koga.hopi` plugin gives every non-debuggable variant: hopi-android's public API,
// and nothing else. No dependency, no manifest entry, no hook, so a release build holds none of the
// capture code.
kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with hopi-android's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    android {
        namespace = "dev.koga.hopi.android.noop"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }

    sourceSets {
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
