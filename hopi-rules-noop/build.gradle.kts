import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with hopi-rules'.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    android {
        namespace = "dev.koga.hopi.rules.noop"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // No dependency at all: never hopi-core.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
